package com.example.online_reader.book.reader;

import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.util.*;
import java.util.zip.CRC32;
import static com.example.online_reader.book.files.EpubPaths.*;

/** One bounded ZIP view of an immutable, already validated source. Nothing is extracted. */
final class EpubDocument implements AutoCloseable {
    record Item(String path,String media,String properties) { }
    record Chapter(String key,String label,String path) { }
    record Toc(String chapter,String label,String anchor) { }
    private static final String OPF="http://www.idpf.org/2007/opf",XHTML="http://www.w3.org/1999/xhtml";
    private final ZipFile zip;
    private final ReaderBudget budget;
    private final Map<String,ZipArchiveEntry> entries=new LinkedHashMap<>();
    private final List<Chapter> chapters=new ArrayList<>();
    private final List<Item> images=new ArrayList<>();
    private final List<Toc> toc=new ArrayList<>();
    private long expanded;
    EpubDocument(SeekableByteChannel channel,ReaderBudget budget)throws IOException {
        this.budget=budget;zip=ZipFile.builder().setSeekableByteChannel(channel).get();
        try {
            var all=zip.getEntries();
            while(all.hasMoreElements()) {
                budget.check();var entry=all.nextElement();
                if(entries.size()>=2000 || !safeName(entry.getName()) || entry.isUnixSymlink() || !zip.canReadEntryData(entry)
                        || entry.getSize()<0 || entry.getSize()>8L*1024*1024 || entries.putIfAbsent(entry.getName(),entry)!=null)throw new IOException("Invalid EPUB");
            }
            metadata();
        }catch(IOException | RuntimeException e){zip.close();throw e;}
    }
    List<Chapter> chapters(){return List.copyOf(chapters);}
    List<Toc> toc(){return List.copyOf(toc);}
    int imageCount(){return images.size();}
    Item image(int index){if(index<0 || index>=images.size())throw new IllegalArgumentException("Invalid resource");return images.get(index);}
    InputStream stream(Item item)throws IOException{return budget.limit(zip.getInputStream(entry(item.path)),8L*1024*1024);}
    long size(Item item)throws IOException{return entry(item.path).getSize();}
    private ZipArchiveEntry entry(String path)throws IOException {
        var entry=entries.get(path);if(entry==null || entry.isDirectory())throw new IOException("Missing entry");return entry;
    }
    private byte[] bytes(String path)throws IOException {
        var entry=entry(path);
        if(entry.getSize()+expanded>32L*1024*1024)throw new IOException("EPUB expansion limit");
        byte[] bytes;
        try(var input=budget.limit(zip.getInputStream(entry),8L*1024*1024)){bytes=input.readAllBytes();}
        expanded+=bytes.length;var crc=new CRC32();crc.update(bytes);
        if(bytes.length!=entry.getSize() || crc.getValue()!=entry.getCrc() || (bytes.length>0 && bytes.length/(double)Math.max(1,entry.getCompressedSize())>100))throw new IOException("Invalid entry");
        return bytes;
    }
    ChapterText chapter(int index,String anchor)throws IOException{return ChapterText.parse(bytes(chapters.get(index).path),anchor,budget);}
    ChapterText chapter(int index,String anchor,int offset)throws IOException{return ChapterText.parse(bytes(chapters.get(index).path),anchor,offset,budget);}
    String imageUrl(int chapter,String href,String fingerprint) {
        try {
            String path=reference(directory(chapters.get(chapter).path),href);
            for(int i=0;i<images.size();i++)if(images.get(i).path.equals(path))return "resources/"+i+"?fingerprint="+fingerprint+"&version=1";
        }catch(RuntimeException ignored){/* External and unsupported images remain text captions. */}
        return null;
    }
    private void metadata()throws IOException {
        var roots=new ArrayList<String>();
        ReaderXml.read(bytes("META-INF/container.xml"),budget,(r,d)->{
            if(r.isStartElement() && r.getName().equals(new javax.xml.namespace.QName("urn:oasis:names:tc:opendocument:xmlns:container","rootfile")))roots.add(reference("",ReaderXml.attr(r,"full-path")));
        });
        if(roots.size()!=1)throw new IOException("Invalid package");
        String path=roots.getFirst();var manifest=new LinkedHashMap<String,Item>();var spine=new ArrayList<String>();String[] tocId={null},version={null};
        ReaderXml.read(bytes(path),budget,(r,d)->{
            if(!r.isStartElement() || !OPF.equals(r.getNamespaceURI()))return;
            switch(r.getLocalName()) {
                case "package"->version[0]=ReaderXml.attr(r,"version");
                case "item"->{String id=ReaderXml.attr(r,"id");var item=new Item(reference(directory(path),ReaderXml.attr(r,"href")),ReaderXml.attr(r,"media-type"),ReaderXml.attr(r,"properties"));if(id==null || item.media==null || item.media.isBlank() || manifest.putIfAbsent(id,item)!=null)throw new IOException("Invalid manifest");}
                case "spine"->tocId[0]=ReaderXml.attr(r,"toc");
                case "itemref"->spine.add(ReaderXml.attr(r,"idref"));
                default->{ }
            }
        });
        for(String id:spine) {
            var item=manifest.get(id);if(item==null || !"application/xhtml+xml".equals(item.media))throw new IOException("Invalid spine");
            chapters.add(new Chapter("s"+chapters.size(),"Глава "+(chapters.size()+1),item.path));
        }
        if(chapters.isEmpty())throw new IOException("Empty spine");
        for(var item:manifest.values())if(Set.of("image/png","image/jpeg").contains(item.media))images.add(item);
        Item navigation="2.0".equals(version[0])?manifest.get(tocId[0]):manifest.values().stream().filter(i->i.properties!=null && Arrays.asList(i.properties.split("\\s+")).contains("nav")).findFirst().orElse(null);
        if(navigation==null)throw new IOException("Missing TOC");
        navigation(navigation,"2.0".equals(version[0]));
        for(int i=0;i<chapters.size();i++) {
            var chapter=chapters.get(i);String label=toc.stream().filter(t->t.chapter.equals(chapter.key)).map(Toc::label).findFirst().orElse(chapter.label);
            chapters.set(i,new Chapter(chapter.key,label,chapter.path));
        }
        if(toc.isEmpty())for(var chapter:chapters)toc.add(new Toc(chapter.key,chapter.label,null));
    }
    private void navigation(Item item,boolean ncx)throws IOException {
        final class Link {String href;final StringBuilder label=new StringBuilder();int depth,order;}
        var stack=new ArrayDeque<Link>();int[] navDepth={0},labelDepth={0},sequence={0};
        var ordered=new TreeMap<Integer,Toc>();
        ReaderXml.read(bytes(item.path),budget,(r,depth)->{
            String name=(r.isStartElement() || r.isEndElement())?r.getLocalName():"";
            String namespace=(r.isStartElement() || r.isEndElement())?r.getNamespaceURI():"";
            if(r.isStartElement()) {
                if(!ncx && XHTML.equals(namespace) && name.equals("nav") && Arrays.asList(Objects.toString(r.getAttributeValue("http://www.idpf.org/2007/ops","type"),"").split("\\s+")).contains("toc"))navDepth[0]=depth;
                if(ncx && "http://www.daisy.org/z3986/2005/ncx/".equals(namespace)) {
                    if(name.equals("navPoint")){var link=new Link();link.depth=depth;link.order=sequence[0]++;stack.push(link);}
                    if(!stack.isEmpty() && name.equals("content"))stack.peek().href=ReaderXml.attr(r,"src");
                    if(name.equals("navLabel"))labelDepth[0]=depth;
                }else if(!ncx && navDepth[0]>0 && XHTML.equals(namespace) && name.equals("a")) {
                    var link=new Link();link.depth=depth;link.order=sequence[0]++;link.href=ReaderXml.attr(r,"href");stack.push(link);
                }
            }else if(r.isCharacters() && !stack.isEmpty() && (!ncx || labelDepth[0]>0)) {
                var label=stack.peek().label;if(label.length()<400)label.append(r.getText(),0,Math.min(r.getTextLength(),400-label.length()));
            }else if(r.isEndElement()) {
                if(!stack.isEmpty() && stack.peek().depth==depth) {
                    var link=stack.pop();var entry=tocEntry(item.path,link.href,link.label.toString());
                    if(entry!=null){ordered.put(link.order,entry);if(ordered.size()>2000)ordered.pollLastEntry();}
                }
                if(navDepth[0]==depth)navDepth[0]=0;if(labelDepth[0]==depth)labelDepth[0]=0;
            }
        });
        toc.addAll(ordered.values());
    }
    private Toc tocEntry(String base,String href,String label) {
        if(href==null)return null;
        try {
            var uri=URI.create(href);if(uri.isAbsolute() || uri.getRawAuthority()!=null || uri.getRawQuery()!=null)return null;
            String path=uri.getRawPath().isEmpty()?base:reference(directory(base),uri.getRawPath());
            String anchor=uri.getFragment();if(anchor!=null && anchor.length()>1024)return null;
            String title=label.strip().replaceAll("\\s+"," ");title=title.codePoints().limit(200).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
            for(var chapter:chapters)if(chapter.path.equals(path))return new Toc(chapter.key,title.isBlank()?chapter.label:title,anchor);
        }catch(RuntimeException ignored){/* Navigation outside the spine is inert. */}
        return null;
    }
    @Override public void close()throws IOException{zip.close();}
}
