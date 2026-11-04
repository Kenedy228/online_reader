package com.example.online_reader.book.notes;

import jakarta.validation.constraints.*;

public class NoteDeleteForm {
    @NotNull @Min(0) @Max(2147483646) private Integer version;
    @AssertTrue(message="Подтвердите удаление заметки") private boolean confirmed;
    public Integer getVersion(){return version;}public void setVersion(Integer value){version=value;}
    public boolean isConfirmed(){return confirmed;}public void setConfirmed(boolean value){confirmed=value;}
}
