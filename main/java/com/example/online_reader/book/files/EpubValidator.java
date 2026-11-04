package com.example.online_reader.book.files;

import org.apache.commons.compress.archivers.zip.*;
import static com.example.online_reader.book.files.EpubPaths.*;
import org.apache.commons.compress.utils.InputStreamStatistics;
import javax.xml.stream.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Validate a bounded reflowable subset. Originals remain opaque attachments, never origin HTML. */
final class EpubValidator {
    private static final String OPF="http://www.idpf.org/2007/opf", XHTML="http://www.w3.org/1999/xhtml";
    private final ProcessingBudget budget;
    private long expanded;
    private record Item(String path,String media,String properties) { }
    EpubValidator(ProcessingBudget budget){this.budget=budget;}
    void validate(Path source)throws IOException,XMLStreamException {
        try(var input=Files.newInputStream(source)) {
            if(!Arrays.equals(input.readNBytes(4),new byte[]{'P','K',3,4}))throw BookFileException.invalid();
        }
        try(var zip=ZipFile.builder().setPath(source).get()) {
            var entries=new LinkedHashMap<String,ZipArchiveEntry>();
            var order=zip.getEntriesInPhysicalOrder();int count=0;
            while(order.hasMoreElements()) {
                budget.check();var e=order.nextElement();
                if(++count>2000 || !rawNameSafe(e) || !safeName(e.getName()) || entries.putIfAbsent(e.getName(),e)!=null
                        || e.isUnixSymlink() || !zip.canReadEntryData(e) || (e.getMethod()!=0 && e.getMethod()!=8)
                        || (e.getUnixMode() & 0170000)!=0 && (e.getUnixMode() & 0170000)!=0100000 && (e.getUnixMode() & 0170000)!=0040000)
                    throw BookFileException.invalid();
            }
            if(entries.isEmpty() || !entries.keySet().iterator().next().equals("mimetype")
                    || entries.get("mimetype").getMethod()!=0)throw BookFileException.invalid();
            var container=entries.get("META-INF/container.xml");
            if(container==null)throw BookFileException.invalid();
            var roots=new ArrayList<String>();
            xml(read(zip,container),(r,path)->{
                if(r.isStartElement() && path.size()==1 && (!at(path,"urn:oasis:names:tc:opendocument:xmlns:container","container") || !"1.0".equals(attr(r,"version"))))throw BookFileException.invalid();
                if(start(r,"rootfile")) {
                    if(!at(path,"urn:oasis:names:tc:opendocument:xmlns:container","container","rootfiles","rootfile") || !"application/oebps-package+xml".equals(attr(r,"media-type")))throw BookFileException.invalid();
                    roots.add(reference("",attr(r,"full-path")));if(roots.size()>1)throw BookFileException.invalid();
                }
            });
            if(roots.size()!=1)throw BookFileException.invalid();
            String packagePath=roots.getFirst();var packageEntry=entries.get(packagePath);
            if(packageEntry==null)throw BookFileException.invalid();
            var manifest=new HashMap<String,Item>();var spine=new ArrayList<String>();
            var manifestPaths=new HashSet<String>();
            final String[] version={null},toc={null};final int[] sections={0,0};var layoutText=new StringBuilder();final boolean[] layout={false};
            xml(read(zip,packageEntry),(r,path)->{
                if(r.isStartElement() && path.size()==1) {
                    if(!at(path,OPF,"package") || !Set.of("2.0","3.0").contains(attr(r,"version")))throw BookFileException.invalid();
                    version[0]=attr(r,"version");
                }
                if(start(r,"manifest") && (!at(path,OPF,"package","manifest") || ++sections[0]>1))throw BookFileException.invalid();
                if(start(r,"spine")) {
                    if(!at(path,OPF,"package","spine") || ++sections[1]>1)throw BookFileException.invalid();
                    toc[0]=attr(r,"toc");
                }
                if(start(r,"item")) {
                    if(!at(path,OPF,"package","manifest","item"))throw BookFileException.invalid();
                    String id=attr(r,"id");var item=new Item(reference(directory(packagePath),attr(r,"href")),attr(r,"media-type"),attr(r,"properties"));
                    if(id==null || id.isBlank() || manifest.putIfAbsent(id,item)!=null || !manifestPaths.add(item.path) || manifest.size()>2000 || !entries.containsKey(item.path))throw BookFileException.invalid();
                }
                if(start(r,"itemref")) {
                    if(!at(path,OPF,"package","spine","itemref") || spine.size()>=2000 || hasToken(attr(r,"properties"),"rendition:layout-pre-paginated"))throw BookFileException.invalid();
                    spine.add(attr(r,"idref"));
                }
                if(start(r,"meta") && at(path,OPF,"package","metadata","meta")) {
                    if("fixed-layout".equals(attr(r,"name")) && "true".equals(attr(r,"content")))throw BookFileException.invalid();
                    layout[0]="rendition:layout".equals(attr(r,"property"));layoutText.setLength(0);
                }
                if(layout[0] && r.isCharacters()) {
                    if(layoutText.length()+r.getTextLength()>64)throw BookFileException.invalid();layoutText.append(r.getText());
                }
                if(r.isEndElement() && at(path,OPF,"package","metadata","meta") && layout[0]) {
                    if(!layoutText.toString().strip().equals("reflowable"))throw BookFileException.invalid();layout[0]=false;
                }
            });
            if(version[0]==null || sections[0]!=1 || sections[1]!=1 || spine.isEmpty())throw BookFileException.invalid();
            Item navigation;
            if(version[0].equals("2.0")) {
                navigation=manifest.get(toc[0]);
                if(navigation==null || !"application/x-dtbncx+xml".equals(navigation.media))throw BookFileException.invalid();
            } else {
                var navs=manifest.values().stream().filter(i->hasToken(i.properties,"nav")).toList();
                if(navs.size()!=1 || !"application/xhtml+xml".equals(navs.getFirst().media))throw BookFileException.invalid();navigation=navs.getFirst();
            }
            var textPaths=new HashSet<String>();
            for(String id:spine) {
                var item=manifest.get(id);
                if(item==null || !"application/xhtml+xml".equals(item.media))throw BookFileException.invalid();
                textPaths.add(item.path);
            }
            var protectedFonts=new HashSet<String>();
            var encryption=entries.get("META-INF/encryption.xml");
            if(encryption!=null) {
                final String[] algorithm={null},target={null};final int[] encrypted={0};
                xml(read(zip,encryption),(r,path)->{
                    if(r.isStartElement() && path.size()==1 && !at(path,"urn:oasis:names:tc:opendocument:xmlns:container","encryption"))throw BookFileException.invalid();
                    if(start(r,"EncryptedData")) {
                        if(!encryptionPath(path,"EncryptedData") || ++encrypted[0]>2000)throw BookFileException.invalid();algorithm[0]=null;target[0]=null;
                    }
                    if(start(r,"EncryptionMethod")) {
                        if(!encryptionPath(path,"EncryptedData","EncryptionMethod") || algorithm[0]!=null)throw BookFileException.invalid();algorithm[0]=attr(r,"Algorithm");
                    }
                    if(start(r,"CipherReference")) {
                        if(!encryptionPath(path,"EncryptedData","CipherData","CipherReference") || target[0]!=null)throw BookFileException.invalid();target[0]=reference("",attr(r,"URI"));
                    }
                    if(r.isEndElement() && "EncryptedData".equals(r.getLocalName())) {
                        if(!"http://www.idpf.org/2008/embedding".equals(algorithm[0]) || target[0]==null
                                || manifest.values().stream().noneMatch(i->i.path.equals(target[0]) && font(i.media)))throw BookFileException.invalid();
                        protectedFonts.add(target[0]);
                    }
                });
                if(protectedFonts.isEmpty())throw BookFileException.invalid();
            }
            boolean usable=false;boolean navigationValid=false;
            for(var e:entries.values()) {
                budget.check();
                if(e.isDirectory() || e==container || e==packageEntry || e==encryption)continue;
                byte[] bytes=read(zip,e);
                String name=e.getName();
                if(name.equals("mimetype")) {
                    if(!Arrays.equals(bytes,"application/epub+zip".getBytes(StandardCharsets.US_ASCII)))throw BookFileException.invalid();
                    continue;
                }
                if(protectedFonts.contains(name))continue; // Obfuscated fonts are never decoded or served.
                String lower=name.toLowerCase(Locale.ROOT);
                boolean xhtml=textPaths.contains(name) || version[0].equals("3.0") && name.equals(navigation.path);
                if(xhtml || name.equals(navigation.path) || lower.matches(".*\\.(xml|opf|ncx|xhtml|html|svg)")) {
                    final boolean[] body={false},text={false},tocPresent={false},tocLink={false};final int[] ignored={0},tocDepth={0};
                    xml(bytes,(r,path)->{
                        if(r.isStartElement()) {
                            if(path.size()==1 && xhtml && !at(path,XHTML,"html"))throw BookFileException.invalid();
                            if(path.size()==1 && name.equals(navigation.path) && version[0].equals("2.0") && !at(path,"http://www.daisy.org/z3986/2005/ncx/","ncx"))throw BookFileException.invalid();
                            if(name.equals(navigation.path) && (("nav".equals(r.getLocalName()) && XHTML.equals(r.getNamespaceURI()) && hasToken(r.getAttributeValue("http://www.idpf.org/2007/ops","type"),"toc")) || "navMap".equals(r.getLocalName()) && "http://www.daisy.org/z3986/2005/ncx/".equals(r.getNamespaceURI()))) {tocPresent[0]=true;tocDepth[0]=path.size();}
                            if(tocDepth[0]>0 && ("a".equals(r.getLocalName()) || "content".equals(r.getLocalName()))) {
                                String href=attr(r,"a".equals(r.getLocalName())?"href":"src");
                                if(href!=null) {
                                    try {String local=href.split("#",2)[0];String dest=local.isEmpty()?name:reference(directory(name),local);if(textPaths.contains(dest))tocLink[0]=true;}
                                    catch(BookFileException | IllegalArgumentException ignoredLink) { /* External links remain inert; no fetch. */ }
                                }
                            }
                            if("body".equals(r.getLocalName()) && XHTML.equals(r.getNamespaceURI()))body[0]=true;
                            if(Set.of("script","style","head","svg","math","iframe","object","audio","video").contains(r.getLocalName()))ignored[0]++;
                        }
                        if(body[0] && ignored[0]==0 && r.isCharacters() && !r.getText().isBlank())text[0]=true;
                        if(r.isEndElement()) {
                            if(path.size()==tocDepth[0])tocDepth[0]=0;
                            if(Set.of("script","style","head","svg","math","iframe","object","audio","video").contains(r.getLocalName()))ignored[0]--;
                            if("body".equals(r.getLocalName()))body[0]=false;
                        }
                    });
                    if(textPaths.contains(name) && text[0])usable=true;
                    if(name.equals(navigation.path) && tocPresent[0] && tocLink[0])navigationValid=true;
                }
                var media=manifest.values().stream().filter(i->i.path.equals(name)).map(Item::media).findFirst().orElse("");
                if(media.equals("image/png") || media.equals("image/jpeg") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))image(bytes,media);
                if(lower.endsWith(".zip") || lower.endsWith(".epub"))throw BookFileException.invalid();
            }
            if(!usable || !navigationValid)throw BookFileException.invalid();
        }
    }
    private byte[] read(ZipFile zip,ZipArchiveEntry entry)throws IOException {
        try(var raw=zip.getInputStream(entry);var in=budget.limit(raw,8*1024*1024);var out=new ByteArrayOutputStream()) {
            var crc=new CRC32();byte[] buffer=new byte[8192];int n;long size=0;
            while((n=in.read(buffer))!=-1) {
                budget.check();size+=n;expanded+=n;
                if(expanded>32*1024*1024)throw BookFileException.invalid();
                crc.update(buffer,0,n);out.write(buffer,0,n);
                if(raw instanceof InputStreamStatistics stats && size>Math.max(1,stats.getCompressedCount())*100L)throw BookFileException.invalid();
            }
            if(entry.getCrc()!=crc.getValue() || entry.getSize()!=size)throw BookFileException.invalid();
            return out.toByteArray();
        }
    }
    @FunctionalInterface private interface Observer {void event(XMLStreamReader reader,java.util.List<javax.xml.namespace.QName> path)throws XMLStreamException;}
    private void xml(byte[] bytes,Observer observer)throws XMLStreamException {
        var factory=XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External XML forbidden");});
        var r=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));int depth=0;var path=new ArrayList<javax.xml.namespace.QName>();
        try {
            while(r.hasNext()) {
                budget.check();int token=r.next();
                if(token==XMLStreamConstants.DTD || token==XMLStreamConstants.ENTITY_REFERENCE)throw BookFileException.invalid();
                if(r.isStartElement() && (++depth>128 || r.getAttributeCount()+r.getNamespaceCount()>64 || "http://www.w3.org/2001/XInclude".equals(r.getNamespaceURI())))throw BookFileException.invalid();
                if(r.isStartElement())path.add(r.getName());
                observer.event(r,path);
                if(r.isEndElement()){depth--;path.removeLast();}
            }
        } finally{r.close();}
    }
    private void image(byte[] bytes,String media)throws IOException {
        try(var stream=new MemoryCacheImageInputStream(budget.limit(new ByteArrayInputStream(bytes),8L*1024*1024))) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw BookFileException.invalid();
            var reader=readers.next();
            try {
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format) || media.equals("image/png") && !format.equals("png")
                        || media.equals("image/jpeg") && format.equals("png"))throw BookFileException.invalid();
                reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1 || h<1 || w>8192 || h>8192 || (long)w*h>16000000)throw BookFileException.invalid();
                budget.check();reader.read(0);budget.check();
            } finally {reader.dispose();}
        }
    }
    private static boolean at(List<javax.xml.namespace.QName> path,String namespace,String... names) {
        if(path.size()!=names.length)return false;
        for(int i=0;i<names.length;i++)if(!path.get(i).equals(new javax.xml.namespace.QName(namespace,names[i])))return false;
        return true;
    }
    private static boolean encryptionPath(List<javax.xml.namespace.QName> path,String... names) {
        if(path.size()!=names.length+1 || !path.getFirst().equals(new javax.xml.namespace.QName("urn:oasis:names:tc:opendocument:xmlns:container","encryption")))return false;
        for(int i=0;i<names.length;i++)if(!path.get(i+1).equals(new javax.xml.namespace.QName("http://www.w3.org/2001/04/xmlenc#",names[i])))return false;
        return true;
    }
    private static boolean start(XMLStreamReader r,String name){return r.isStartElement() && name.equals(r.getLocalName());}
    private static String attr(XMLStreamReader r,String name){return r.getAttributeValue(null,name);}
    private static boolean hasToken(String value,String token){return value!=null && Arrays.asList(value.split("\\s+")).contains(token);}
    private static boolean font(String media){return media!=null && Set.of("font/otf","font/ttf","font/woff","font/woff2","application/vnd.ms-opentype","application/font-sfnt","application/font-woff").contains(media);}
    private static boolean rawNameSafe(ZipArchiveEntry entry) {
        byte[] name=entry.getRawName();if(name==null)return false;
        for(byte value:name)if(value==92)return false;
        return true;
    }

}
