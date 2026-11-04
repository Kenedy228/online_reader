package com.example.online_reader.book.DTO;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public class BookMetadataForm {
    @NotBlank(message = "Укажите название")
    @CodePointLength(max = 200, message = "Не больше 200 символов")
    @Pattern(regexp = "[^\\p{Cc}]*", message = "Уберите управляющие символы")
    private String title;
    @NotNull @CodePointLength(max = 200, message = "Не больше 200 символов")
    @Pattern(regexp = "[^\\p{Cc}]*", message = "Уберите управляющие символы")
    private String author = "";
    @NotNull @PositiveOrZero private Long version;
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
