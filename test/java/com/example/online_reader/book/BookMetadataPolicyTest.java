package com.example.online_reader.book;

import com.example.online_reader.book.DTO.BookMetadataForm;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BookMetadataPolicyTest {
    @Test void lengthsCountUnicodeCodePointsRatherThanUtf16Units() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var form=new BookMetadataForm();form.setVersion(0L);form.setTitle("🙂".repeat(200));form.setAuthor("🙂".repeat(200));
            assertThat(factory.getValidator().validate(form)).isEmpty();
            form.setTitle("🙂".repeat(201));form.setAuthor("🙂".repeat(201));
            assertThat(factory.getValidator().validate(form)).extracting(v->v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("title","author");
        }
    }
}
