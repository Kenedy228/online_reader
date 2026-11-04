package com.example.online_reader.book.reader;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.assertj.core.api.Assertions.*;

class TextFragmentTest {
    @Test void normalizesLineEndingsAndCountsUnicodeCodePointsRatherThanUtf16() throws Exception {
        var result=TextFragment.read(new StringReader("\ufeffA\r\n🙂\rB"),2,2);
        assertThat(result.text()).isEqualTo("🙂\n");
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.next()).isEqualTo(4);
    }
}
