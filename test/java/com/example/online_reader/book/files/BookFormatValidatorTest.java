package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class BookFormatValidatorTest {
    @TempDir Path directory;
    @Test void acceptsStrictUtf8TextAndRejectsBinaryEmptyAndMalformedInput() throws Exception {
        var validator=new BookFormatValidator();
        var source=directory.resolve("source");
        Files.writeString(source,"\ufeffПривет\r\n🙂");
        validator.validate(source,Book.Format.TXT);
        for(byte[] invalid:new byte[][]{new byte[0]," \r\n".getBytes(StandardCharsets.UTF_8),{0},{(byte)0xc0,(byte)0xaf}}) {
            Files.write(source,invalid);
            assertThatThrownBy(()->validator.validate(source,Book.Format.TXT)).isInstanceOf(BookFileException.class);
        }
    }
}
