package com.example.online_reader.book.files;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Single application instance owns this local root. No submitted names become paths. */
@Component
public class PrivateBookStorage implements AutoCloseable {
    private static final long TEMP_LIMIT=128L*1024*1024, JOB_RESERVATION=32L*1024*1024;
    private final Path root,sources,staging;
    private final UUID identity;
    private final FileChannel leaseChannel;
    private final FileLock lease;
    private final Set<Job> active=new HashSet<>();
    private final Set<UUID> writers=new HashSet<>();
    private final Map<UUID,Integer> readers=new HashMap<>();
    public PrivateBookStorage(@Value("${online-reader.storage.root}") String location)throws IOException {
        root=Path.of(location).toAbsolutePath().normalize();
        for(Path parent=root;parent!=null;parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException("Invalid storage root");
        for(String publicPath:List.of("main/resources","target/classes","target/generated-resources"))
            if(root.startsWith(Path.of(publicPath).toAbsolutePath().normalize()))throw new IOException("Invalid storage root");
        privateDirectory(root);sources=root.resolve("sources");staging=root.resolve("staging");
        privateDirectory(sources);privateDirectory(staging);
        Path lock=root.resolve(".lease");if(Files.isSymbolicLink(lock))throw new IOException("Invalid storage lease");
        leaseChannel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try{acquired=leaseChannel.tryLock();}catch(OverlappingFileLockException e){acquired=null;}
        if(acquired==null){leaseChannel.close();throw new IOException("Storage root is already in use");}
        lease=acquired;
        try{
            Path marker=root.resolve(".identity");
            if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))Files.writeString(marker,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || Files.size(marker)!=36)throw new IOException("Invalid storage identity");
            identity=UUID.fromString(Files.readString(marker));
            cleanupStaging();
        }catch(IOException | RuntimeException e){lease.release();leaseChannel.close();throw e;}
    }
    private static void privateDirectory(Path path)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            if(path.getFileSystem().supportedFileAttributeViews().contains("posix"))Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else {
                Files.createDirectories(path);
                var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(acl==null)throw new IOException("Cannot verify private storage permissions");
                acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                        .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                        .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT).build()));
            }
        }
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path) || !Files.isWritable(path))throw new IOException("Invalid storage permissions");
        if(path.getFileSystem().supportedFileAttributeViews().contains("posix") && Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))throw new IOException("Invalid storage permissions");
        if(!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            var owner=Files.getOwner(path);
            if(acl==null || !privateAcl(owner,acl.getAcl()))throw new IOException("Invalid storage permissions");
        }
    }
    static boolean privateAcl(java.nio.file.attribute.UserPrincipal owner,List<java.nio.file.attribute.AclEntry> entries) {
        // Windows exposes NULL DACL as an empty list too; absence of ALLOW entries is not proof of privacy.
        return entries.stream().anyMatch(e->e.type()==java.nio.file.attribute.AclEntryType.ALLOW && e.principal().equals(owner))
                && entries.stream().noneMatch(e->e.type()==java.nio.file.attribute.AclEntryType.ALLOW && !e.principal().equals(owner));
    }
    public synchronized Job admit()throws IOException {
        cleanupStaging();long occupied=stagingSize();
        if(active.stream().filter(j->!j.slotReleased).count()>=2 || occupied+JOB_RESERVATION*(active.size()+1)>TEMP_LIMIT)throw BookFileException.busy();
        var job=new Job(UUID.randomUUID());active.add(job);return job;
    }
    public final class Job implements AutoCloseable {
        private final UUID id;
        private boolean released,slotReleased,working,closeRequested;
        private Job(UUID id){this.id=id;}
        public void beginWork(){synchronized(PrivateBookStorage.this){if(closeRequested)throw BookFileException.busy();working=true;}}
        public void finishWork(){synchronized(PrivateBookStorage.this){working=false;if(closeRequested)close();}}
        public Path request(){return staging.resolve(id+".request");}
        public Path source(){return staging.resolve(id+".source");}
        @Override public void close(){
            synchronized(PrivateBookStorage.this){
                slotReleased=true;closeRequested=true;
                if(released || working)return;released=true;
                // Failed deletes remain visible to stagingSize(), never forgiven as free space.
                removeIfPossible(request());removeIfPossible(source());active.remove(this);
            }
        }
    }
    private long stagingSize()throws IOException {
        long count=0;
        try(var paths=Files.list(staging)) {
            for(Path p:paths.toList()) {
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid staging entry");
                count=Math.addExact(count,Files.size(p));
            }
        }
        return count;
    }
    private void cleanupStaging()throws IOException {
        try(var paths=Files.list(staging)) {
            for(Path p:paths.toList()) {
                String name=p.getFileName().toString();
                if(!name.matches("[0-9a-f-]{36}\\.(request|source)") || !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid staging entry");
                if(active.stream().noneMatch(j->p.equals(j.request()) || p.equals(j.source())))removeIfPossible(p);
            }
        }
    }
    private static void removeIfPossible(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){/* Retained and counted for retry. */}}
    public UUID identity(){return identity;}
    public synchronized void beginWrite(UUID key){if(!writers.add(key))throw new IllegalStateException("Source is busy");}
    public synchronized void endWrite(UUID key){writers.remove(key);}
    public synchronized boolean writing(UUID key){return writers.contains(key);}
    public void persist(UUID key,Path temporary,ProcessingBudget budget)throws IOException {
        Path target=path(key);
        try(var input=budget.limit(Files.newInputStream(temporary),10*1024*1024);
            var channel=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var output=Channels.newOutputStream(channel)) {
            input.transferTo(output);output.flush();channel.force(true);budget.check();
        }
    }
    public synchronized InputStream open(UUID key,long expected)throws IOException {
        var channel=FileChannel.open(path(key),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        if(channel.size()!=expected){channel.close();throw new IOException("Invalid source size");}
        readers.merge(key,1,Integer::sum);
        return new FilterInputStream(Channels.newInputStream(channel)) {
            boolean closed;
            @Override public void close()throws IOException {
                synchronized(PrivateBookStorage.this) {
                    if(closed)return;closed=true;
                    try{super.close();}finally{readers.computeIfPresent(key,(k,n)->n==1?null:n-1);}
                }
            }
        };
    }
    public synchronized boolean removeIfIdle(UUID key)throws IOException {
        if(writers.contains(key) || readers.containsKey(key))return false;
        Files.deleteIfExists(path(key));return true;
    }
    private Path path(UUID key){return sources.resolve(Objects.requireNonNull(key)+".book");}
    @PreDestroy @Override public void close()throws IOException {
        synchronized(this){for(Job job:List.copyOf(active))job.close();}
        lease.release();leaseChannel.close();
    }
}
