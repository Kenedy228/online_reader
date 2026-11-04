package com.example.online_reader.book.DTO;

import jakarta.validation.constraints.*;

public class BookDeleteForm {
    @NotNull @PositiveOrZero private Long version;
    @AssertTrue(message = "Подтвердите удаление книги") private boolean confirmed;
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
}
