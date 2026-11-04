package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EpubValidatorTest {
    @TempDir Path directory;
    final BookFormatValidator validator=new BookFormatValidator();
    void reject(Map<String,byte[]> files)throws Exception {
        Path path=EpubFixtures.write(directory.resolve(UUID.randomUUID()+".epub"),files);
        assertThat(Files.size(path)).isLessThanOrEqualTo(10L*1024*1024);
        assertThatThrownBy(()->validator.validate(path,Book.Format.EPUB)).isInstanceOf(BookFileException.class);
    }
    @Test void validReflowableBookAndFontObfuscationAreAcceptedWithoutDecodingFonts()throws Exception {
        var files=EpubFixtures.content();validator.validate(EpubFixtures.write(directory.resolve("valid"),files),Book.Format.EPUB);
        String opf=new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("</manifest>","<item id='font' href='font.otf' media-type='font/otf'/></manifest>");
        files.put("OPS/book.opf",EpubFixtures.bytes(opf));files.put("OPS/font.otf",new byte[]{1,2,3});
        files.put("META-INF/encryption.xml",EpubFixtures.bytes("<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><e:CipherData><e:CipherReference URI='OPS/font.otf'/></e:CipherData></e:EncryptedData></encryption>"));
        validator.validate(EpubFixtures.write(directory.resolve("obfuscated"),files),Book.Format.EPUB);
    }
    @Test void acceptsEpub2NcxAndEpub3NavigationInSpineWithFragmentOnlyLinks()throws Exception {
        var epub2=EpubFixtures.content();String opf=new String(epub2.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8);
        opf=opf.replace("version='3.0'","version='2.0'").replace("<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>","<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>").replace("<spine>","<spine toc='ncx'>");
        epub2.put("OPS/book.opf",EpubFixtures.bytes(opf));epub2.remove("OPS/nav.xhtml");
        epub2.put("OPS/toc.ncx",EpubFixtures.bytes("<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/' version='2005-1'><head/><docTitle><text>Книга</text></docTitle><navMap><navPoint id='c1'><navLabel><text>Начало</text></navLabel><content src='text.xhtml'/></navPoint></navMap></ncx>"));
        validator.validate(EpubFixtures.write(directory.resolve("epub2"),epub2),Book.Format.EPUB);
        var epub3=EpubFixtures.content();opf=new String(epub3.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>","").replace("href='text.xhtml' media-type='application/xhtml+xml'","href='text.xhtml' media-type='application/xhtml+xml' properties='nav'");
        epub3.put("OPS/book.opf",EpubFixtures.bytes(opf));epub3.remove("OPS/nav.xhtml");
        epub3.put("OPS/text.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Книга</title></head><body><nav epub:type='toc'><a href='#chapter'>Начало</a></nav><h1 id='chapter'>Глава</h1><p>Текст</p></body></html>"));
        validator.validate(EpubFixtures.write(directory.resolve("epub3-self"),epub3),Book.Format.EPUB);
    }
    @Test void rejectsActualEntryAndTotalExpansionLimitsAndOversizedRaster()throws Exception {
        var entry=EpubFixtures.content();byte[] large=new byte[8*1024*1024+1];new Random(17).nextBytes(large);entry.put("oversize.bin",large);reject(entry);
        var total=EpubFixtures.content();
        for(int i=0;i<5;i++) {
            byte[] data=new byte[7*1024*1024];var random=new Random(i+42);
            for(int j=0;j<data.length;j+=4)data[j]=(byte)random.nextInt(16);
            total.put("expanded"+i+".bin",data);
        }
        reject(total);
        var raster=EpubFixtures.content();var pixels=new java.awt.image.BufferedImage(8193,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",output);raster.put("too-wide.png",output.toByteArray());reject(raster);
    }
    @ParameterizedTest @ValueSource(strings={"../escape","/absolute","C:/file","bad\\file","a/../b","%2e%2e/escape"})
    void rejectsArchivePaths(String name)throws Exception {var files=EpubFixtures.content();files.put(name,new byte[]{1});reject(files);}
    @Test void rejectsXxeXincludeDepthAndAttributeBombs()throws Exception {
        for(String xml:List.of("<!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><html>&xxe;</html>",
                "<html xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='http://127.0.0.1:9/ssrf'/></html>",
                "<a>".repeat(129)+"x"+"</a>".repeat(129),"<a "+java.util.stream.IntStream.range(0,65).mapToObj(i->"x"+i+"='1'").collect(java.util.stream.Collectors.joining(" "))+"/>")) {
            var files=EpubFixtures.content();files.put("OPS/text.xhtml",EpubFixtures.bytes(xml));reject(files);
        }
    }
    @Test void rejectsDrmAndUnknownEncryptionButDoesNotTreatItsFilenameAsProof()throws Exception {
        for(String algorithm:List.of("http://www.w3.org/2001/04/xmlenc#aes256-cbc","unknown","http://www.idpf.org/2008/embedding")) {
            var files=EpubFixtures.content();files.put("META-INF/encryption.xml",EpubFixtures.bytes("<encryption xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='"+algorithm+"'/><e:CipherData><e:CipherReference URI='OPS/text.xhtml'/></e:CipherData></e:EncryptedData></encryption>"));reject(files);
        }
    }
    @Test void rejectsRemoteManifestFixedLayoutMissingSpineAndEmptyText()throws Exception {
        for(String change:List.of("remote","fixed","missing","svg","empty","container")) {
            var files=EpubFixtures.content();String opf=new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8);
            switch(change){
                case "remote"->opf=opf.replace("href='text.xhtml'","href='https://example.invalid/book.xhtml'");
                case "fixed"->opf=opf.replace("</metadata>","<meta property='rendition:layout'>pre-paginated</meta></metadata>");
                case "missing"->opf=opf.replace("idref='text'","idref='missing'");
                case "svg"->opf=opf.replace("id='text' href='text.xhtml' media-type='application/xhtml+xml'","id='text' href='text.xhtml' media-type='image/svg+xml'");
                case "empty"->files.put("OPS/text.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml'><body><script>not text</script></body></html>"));
                case "container"->files.put("META-INF/container.xml",EpubFixtures.bytes("<not-container><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></not-container>"));
            }
            files.put("OPS/book.opf",EpubFixtures.bytes(opf));reject(files);
        }
    }
    @Test void rejectsSplitLayoutNestedEncryptionWrongPackageRootAndMissingNavigation()throws Exception {
        for(String change:List.of("layout","nested-encryption","package-root","missing-nav")) {
            var files=EpubFixtures.content();String opf=new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8);
            switch(change) {
                case "layout"->opf=opf.replace("</metadata>","<meta property='rendition:layout'>pre-<![CDATA[paginated]]></meta></metadata>");
                case "package-root"->opf="<evil>"+opf+"</evil>";
                case "missing-nav"->{opf=opf.replace("<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>","");files.remove("OPS/nav.xhtml");}
                case "nested-encryption"->{
                    opf=opf.replace("</manifest>","<item id='font' href='font.otf' media-type='font/otf'/></manifest>");files.put("OPS/font.otf",new byte[]{1});
                    files.put("META-INF/encryption.xml",EpubFixtures.bytes("<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='UNKNOWN'/><e:CipherData><e:CipherReference URI='OPS/text.xhtml'/></e:CipherData><e:EncryptedData><e:EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><e:CipherData><e:CipherReference URI='OPS/font.otf'/></e:CipherData></e:EncryptedData></e:EncryptedData></encryption>"));
                }
            }
            files.put("OPS/book.opf",EpubFixtures.bytes(opf));reject(files);
        }
    }
    @Test void fontAliasCannotHideEncryptedBinarySpine()throws Exception {
        var files=EpubFixtures.content();String opf=new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8)
                .replace("</manifest>","<item id='font-alias' href='text.xhtml' media-type='font/otf'/><item id='other' href='other.xhtml' media-type='application/xhtml+xml'/></manifest>")
                .replace("</spine>","<itemref idref='other'/></spine>");
        files.put("OPS/book.opf",EpubFixtures.bytes(opf));files.put("OPS/other.xhtml",files.get("OPS/text.xhtml"));files.put("OPS/text.xhtml",new byte[]{0,1,2,3});
        files.put("META-INF/encryption.xml",EpubFixtures.bytes("<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><e:CipherData><e:CipherReference URI='OPS/text.xhtml'/></e:CipherData></e:EncryptedData></encryption>"));
        reject(files);
    }
    @Test void rejectsBombsNestedArchivesAndTooManyEntries()throws Exception {
        var bomb=EpubFixtures.content();bomb.put("bomb.txt",new byte[1024*1024]);reject(bomb);
        var nested=EpubFixtures.content();nested.put("nested.zip",new byte[]{1,2,3});reject(nested);
        var many=EpubFixtures.content();for(int i=0;i<2000;i++)many.put("entry"+i,new byte[]{1});reject(many);
    }
    @Test void rejectsSymlinksAndDamagedArchives()throws Exception {
        var file=directory.resolve("link.epub");
        try(var zip=new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file)) {
            var entry=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("link");entry.setUnixMode(0120777);zip.putArchiveEntry(entry);zip.write(EpubFixtures.bytes("/etc/passwd"));zip.closeArchiveEntry();
        }
        assertThatThrownBy(()->validator.validate(file,Book.Format.EPUB)).isInstanceOf(BookFileException.class);
        Files.write(file,new byte[]{'P','K',3,4,0});
        assertThatThrownBy(()->validator.validate(file,Book.Format.EPUB)).isInstanceOf(BookFileException.class);
    }
    @Test void processingDeadlineRejectsWithoutContinuingParsing()throws Exception {
        var path=EpubFixtures.write(directory.resolve("deadline"),EpubFixtures.content());
        assertThatThrownBy(()->validator.validate(path,Book.Format.EPUB,new ProcessingBudget(java.time.Duration.ZERO)))
                .isInstanceOf(BookFileException.class).hasMessageContaining("время");
    }
}
