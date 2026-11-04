package com.example.online_reader.book.notes;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NoteTextTest {
    @Test void normalizesLineEndingsWithoutInterpretingMarkupAndCountsCodePoints() {
        assertThat(NoteText.normalize("  <script>🙂</script>\r\nвторая\rстрока  ")).isEqualTo("<script>🙂</script>\nвторая\nстрока");
        assertThat(NoteText.normalize("🙂".repeat(2000))).hasSize(4000);
        assertThatThrownBy(()->NoteText.normalize("🙂".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsBlankControlsAndBrokenUnicode() {
        for(String text:new String[]{null," \n\t","a\u0000b","a\u0085b","a\ud800","\udc00"})
            assertThatThrownBy(()->NoteText.normalize(text)).isInstanceOf(IllegalArgumentException.class);
    }
}
