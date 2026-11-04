package com.example.online_reader.book.reader;

import com.example.online_reader.book.files.EpubFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class EpubDocumentTest {
    @TempDir Path temporary;
    @Test void epub3TocFragmentOnlyAndImagesStayInsideImmutableSpine()throws Exception {
        var files=EpubFixtures.content();
        files.put("OPS/book.opf",EpubFixtures.bytes(new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("</manifest>","<item id='image' href='image.png' media-type='image/png'/></manifest>").replace("<itemref idref='text'/>","<itemref idref='nav'/><itemref idref='text'/>")));
        files.put("OPS/text.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml'><body><h1 id='chapter'>Первая</h1><p>Текст</p><img src='image.png' alt='Карта'/></body></html>"));
        files.put("OPS/nav.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body><nav epub:type='toc'><ol><li><a href='#own'>Оглавление</a></li><li><a href='text.xhtml#chapter'>Первая</a></li><li><a href='https://invalid.example/'>Внешняя</a></li></ol></nav><p id='own'>Начало</p></body></html>"));
        files.put("OPS/image.png",new byte[]{1});
        Path path=EpubFixtures.write(temporary.resolve("book.epub"),files);
        try(var channel=FileChannel.open(path);var epub=new EpubDocument(channel,new ReaderBudget())) {
            assertThat(epub.chapters()).extracting(EpubDocument.Chapter::key).containsExactly("s0","s1");
            assertThat(epub.toc()).extracting(EpubDocument.Toc::anchor).containsExactly("own","chapter");
            assertThat(epub.chapter(1,"chapter").anchor()).isZero();
            assertThat(epub.chapter(1,null).render(0,16384,href->epub.imageUrl(1,href,"a".repeat(64))))
                    .contains("resources/0?fingerprint=","&amp;version=1","alt=\"Карта\"");
            assertThat(epub.imageUrl(1,"https://invalid.example/image.png","a".repeat(64))).isNull();
        }
    }
    @Test void malformedAuxiliaryManifestEntryFailsWithControlledParsingError()throws Exception {
        var files=EpubFixtures.content();
        files.put("OPS/book.opf",EpubFixtures.bytes(new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("</manifest>","<item id='self' href='book.opf'/></manifest>")));
        var source=EpubFixtures.write(temporary.resolve("malformed.epub"),files);
        try(var channel=FileChannel.open(source)) {
            assertThatThrownBy(()->new EpubDocument(channel,new ReaderBudget())).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void epub2UsesNcxLabelsAndChapterAnchors()throws Exception {
        var files=EpubFixtures.content();String opf=new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8)
                .replace("version='3.0'","version='2.0'").replace("<spine>","<spine toc='nav'>")
                .replace("href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'","href='toc.ncx' media-type='application/x-dtbncx+xml'");
        files.put("OPS/book.opf",EpubFixtures.bytes(opf));
        files.put("OPS/toc.ncx",EpubFixtures.bytes("<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint id='a'><navLabel><text>Начальная глава</text></navLabel><content src='text.xhtml#start'/><navPoint id='child'><navLabel><text>Подраздел</text></navLabel><content src='text.xhtml#child'/></navPoint></navPoint></navMap></ncx>"));
        try(var channel=FileChannel.open(EpubFixtures.write(temporary.resolve("two.epub"),files));var epub=new EpubDocument(channel,new ReaderBudget())) {
            assertThat(epub.toc()).containsExactly(new EpubDocument.Toc("s0","Начальная глава","start"),new EpubDocument.Toc("s0","Подраздел","child"));
        }
    }
}
