package com.example.online_reader.book.reader;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChapterTextTest {
    @Test void rebuildsSafeMarkupAndKeepsStableUnicodeOffsets()throws Exception {
        var chapter=ChapterText.parse("""
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Hidden</title></head><body>
                <h1 id="start">Глава 🙂</h1><p onclick="evil()">Текст <strong>важный</strong> &lt;script&gt;</p>
                <script>evil()</script><style>body{}</style><a href="https://invalid.example/">Ссылка</a>
                <iframe src="https://invalid.example/"/><img src="bad.svg" alt="Карта"/></body></html>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),"start",new ReaderBudget());
        String html=chapter.render(0,16384,path->null);
        assertThat(html).contains("Глава 🙂","<strong>","важный","&lt;script&gt;","Карта");
        assertThat(html).doesNotContain("onclick","evil()","<script","<style","<iframe","https://","Hidden");
        assertThat(chapter.anchor()).isZero();
    }
    @Test void preservesPreformattedTextAndUsesValidListChildren()throws Exception {
        var chapter=ChapterText.parse("<html xmlns='http://www.w3.org/1999/xhtml'><body><pre>  a\n    b</pre><ul><li>Первый</li><li>Второй</li></ul></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),null,new ReaderBudget());
        String html=chapter.render(0,16384,path->null);
        assertThat(html).contains("  a\n    b");
        assertThat(html).doesNotContain("</li><span", "<ul><span", "<ol><span");
    }
    @Test void lateFragmentRetainsItsOwnHeadingsAndImagesDespiteEarlierFormatting()throws Exception {
        String source="<html xmlns='http://www.w3.org/1999/xhtml'><body>"+"<em>x</em><img src='early.png' alt='a'/>".repeat(300)+"<h1 id='late'>Поздняя</h1><img src='late.png' alt='Карта'/></body></html>";
        var chapter=ChapterText.parse(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"late",new ReaderBudget());
        String html=chapter.render(chapter.anchor(),16384,path->path.equals("late.png")?"resources/0":null);
        assertThat(html).contains("<h1>","Поздняя","src=\"resources/0\"");
        var byOffset=ChapterText.parse(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,chapter.anchor(),new ReaderBudget());
        assertThat(byOffset.render(chapter.anchor(),16384,path->"resources/0")).contains("<h1>","<img");
    }
    @Test void emptyChapterRetainsValidZeroPosition()throws Exception {
        var chapter=ChapterText.parse("<html xmlns='http://www.w3.org/1999/xhtml'><body/></html>".getBytes(),null,new ReaderBudget());
        assertThat(chapter.total()).isZero();assertThat(chapter.render(0,16384,path->null)).isEmpty();
    }
    @Test void encodesPlusInAnchorAsAQueryValue() {
        var page=new BookReader.Page("s0","Глава","",0,1,1,"a".repeat(64),BookReader.Font.normal,java.util.List.of(),java.util.List.of(),0);
        assertThat(page.link("s0",0,"a+b")).contains("anchor=a%2Bb");
    }
    @Test void refusesExternalEntitiesAndExcessiveDepth() {
        assertThatThrownBy(()->ChapterText.parse("<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><html>&x;</html>".getBytes(),null,new ReaderBudget())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ChapterText.parse(("<html xmlns='http://www.w3.org/1999/xhtml'><body>"+"<div>".repeat(130)+"x"+"</div>".repeat(130)+"</body></html>").getBytes(),null,new ReaderBudget())).isInstanceOf(Exception.class);
    }
    @Test void formattingBombCannotDropTextOrGrowTheFragmentBeyondItsBudget()throws Exception {
        var text=ChapterText.parse(("<html xmlns='http://www.w3.org/1999/xhtml'><body>"+"<strong>🙂</strong>".repeat(17000)+"</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8),null,new ReaderBudget());
        String html=text.render(16380,16384,path->null);
        assertThat(text.total()).isEqualTo(17000);
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>",""))).isEqualTo("🙂".repeat(620));
        assertThat(html.length()).isLessThan(262144);
    }
}
