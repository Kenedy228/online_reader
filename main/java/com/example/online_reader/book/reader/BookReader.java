package com.example.online_reader.book.reader;

import com.example.online_reader.book.files.*;
import com.example.online_reader.book.model.Book;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.NoSuchFileException;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

@Service
public class BookReader {
    public static final int FRAGMENT=16384,VERSION=1;
    public enum Font {normal,large,largest}
    public record Chapter(String key,String label) { }
    public record Toc(String chapter,String label,String anchor) { }
    public record Page(String chapter,String label,String html,int offset,int next,int total,String fingerprint,
                       Font font,List<Chapter> chapters,List<Toc> toc,int index) {
        public String link(String chapter,int offset,String anchor) {
            var builder=UriComponentsBuilder.fromPath("read").queryParam("chapter",chapter).queryParam("offset",offset)
                    .queryParam("fingerprint",fingerprint).queryParam("version",VERSION).queryParam("font",font);
            if(anchor!=null)builder.queryParam("anchor","{anchor}");
            return builder.encode().buildAndExpand(anchor==null?Map.of():Map.of("anchor",anchor)).toUriString();
        }
        public int previousOffset(){return Math.max(0,offset-FRAGMENT);}
        public boolean previous(){return offset>0;}
        public boolean following(){return next<total;}
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        Failure(int status){super("Reader unavailable");this.status=status;}
        public int status(){return status;}
    }
    public record Image(InputStream stream,String media,long length) implements AutoCloseable {
        @Override public void close()throws IOException{stream.close();}
    }
    private final FileBookTransactions transactions;
    private final PrivateBookStorage storage;
    private final Semaphore pages=new Semaphore(2),resources=new Semaphore(2,true);
    private final AtomicInteger waiting=new AtomicInteger();
    public BookReader(FileBookTransactions transactions,PrivateBookStorage storage){this.transactions=transactions;this.storage=storage;}
    public Page read(Long id,String chapter,int offset,String fingerprint,Integer version,Font font,String anchor) {
        var source=transactions.download(id); // Live owner/READY policy precedes every filesystem access.
        locator(source,fingerprint,version);
        if(offset<0 || offset>10*1024*1024 || anchor!=null && (anchor.length()>1024 || offset!=0))throw new Failure(400);
        if(!pages.tryAcquire())throw new Failure(503);
        var budget=new ReaderBudget();
        try(var channel=verified(source,budget)) {
            if(source.format()==Book.Format.TXT) {
                if(chapter!=null && !chapter.equals("text") || anchor!=null)throw new Failure(400);
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                var text=TextFragment.read(new InputStreamReader(budget.limit(Channels.newInputStream(channel),10L*1024*1024),decoder),offset,FRAGMENT);
                return new Page("text","Текст",HtmlUtils.htmlEscape(text.text()),offset,text.next(),text.total(),source.fingerprint(),font,List.of(new Chapter("text","Текст")),List.of(),0);
            }
            try(var epub=new EpubDocument(channel,budget)) {
                var chapters=epub.chapters();int index=0;
                if(chapter!=null) {
                    index=-1;for(int i=0;i<chapters.size();i++)if(chapters.get(i).key().equals(chapter)){index=i;break;}
                    if(index<0)throw new Failure(400);
                }
                var text=epub.chapter(index,anchor,offset);
                if(anchor!=null){if(text.anchor()<0)throw new Failure(400);offset=text.anchor();}
                int selected=index;String html=text.render(offset,FRAGMENT,href->epub.imageUrl(selected,href,source.fingerprint()));
                var current=chapters.get(index);
                budget.check();
                return new Page(current.key(),current.label(),html,offset,Math.min(text.total(),offset+FRAGMENT),text.total(),source.fingerprint(),font,
                        chapters.stream().map(c->new Chapter(c.key(),c.label())).toList(),epub.toc().stream().map(t->new Toc(t.chapter(),t.label(),t.anchor())).toList(),index);
            }
        }catch(NoSuchFileException e){throw new Failure(404);}
        catch(IllegalArgumentException e){throw new Failure(400);}
        catch(IOException | BookFileException e){throw new Failure(422);}
        finally{pages.release();}
    }
    public Image image(Long id,int index,String fingerprint,Integer version) {
        var source=transactions.download(id);locator(source,fingerprint,version);
        if(fingerprint==null || source.format()!=Book.Format.EPUB)throw new Failure(400);
        if(waiting.incrementAndGet()>8){waiting.decrementAndGet();throw new Failure(503);}
        boolean acquired;
        try{acquired=resources.tryAcquire(1,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(503);}
        finally{waiting.decrementAndGet();}
        if(!acquired)throw new Failure(503);
        EpubDocument epub=null;SeekableByteChannel channel=null;
        try {
            var budget=new ReaderBudget();channel=verified(source,budget);epub=new EpubDocument(channel,budget);
            if(index<0 || index>=epub.imageCount())throw new Failure(404);
            var item=epub.image(index);
            try(var input=epub.stream(item);var stream=new MemoryCacheImageInputStream(input)) {
                var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Invalid image");
                var reader=readers.next();
                try {
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!Set.of("png","jpeg","jpg").contains(format) || item.media().equals("image/png")!=format.equals("png"))throw new IOException("Invalid image");
                    reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(w<1 || h<1 || w>8192 || h>8192 || (long)w*h>16000000)throw new IOException("Image limit");
                }finally{reader.dispose();}
            }
            var archive=epub;
            var stream=new FilterInputStream(epub.stream(item)) {
                boolean closed;
                @Override public void close()throws IOException {
                    if(closed)return;closed=true;
                    try{try{super.close();}finally{archive.close();}}finally{resources.release();}
                }
            };
            return new Image(stream,item.media(),epub.size(item));
        }catch(IOException | RuntimeException e) {
            try{if(epub!=null)epub.close();else if(channel!=null)channel.close();}catch(IOException ignored){/* Permit must always be released. */}
            resources.release();
            if(e instanceof Failure failure)throw failure;
            if(e instanceof NoSuchFileException)throw new Failure(404);
            throw new Failure(422);
        }
    }
    private static void locator(FileBookTransactions.Source source,String fingerprint,Integer version) {
        if((fingerprint==null)!=(version==null))throw new Failure(400);
        if(fingerprint!=null && (!fingerprint.equals(source.fingerprint()) || version!=VERSION))throw new Failure(409);
    }
    private SeekableByteChannel verified(FileBookTransactions.Source source,ReaderBudget budget)throws IOException {
        var channel=storage.openChannel(source.key(),source.size());
        try {
            if(source.size()<1 || source.size()>10L*1024*1024)throw new IOException("Source limit");
            var digest=MessageDigest.getInstance("SHA-256");var bytes=ByteBuffer.allocate(8192);long count=0;
            while(channel.read(bytes)!=-1) {
                budget.check();count+=bytes.position();if(count>source.size())throw new IOException("Source limit");
                bytes.flip();digest.update(bytes);bytes.clear();
            }
            if(count!=source.size() || !HexFormat.of().formatHex(digest.digest()).equals(source.fingerprint()))throw new IOException("Invalid source fingerprint");
            channel.position(0);return channel;
        }catch(IOException | RuntimeException | NoSuchAlgorithmException e){channel.close();throw new IOException("Invalid source");}
    }
}
