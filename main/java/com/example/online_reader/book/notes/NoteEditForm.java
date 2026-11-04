package com.example.online_reader.book.notes;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public class NoteEditForm {
    @NotBlank(message="Напишите заметку") @CodePointLength(max=2000,message="Не больше 2000 Unicode-символов") private String text;
    @NotNull @Min(0) @Max(2147483646) private Integer version;
    public String getText(){return text;}public void setText(String value){text=NoteText.lineEndings(value);}
    public Integer getVersion(){return version;}public void setVersion(Integer value){version=value;}
}
