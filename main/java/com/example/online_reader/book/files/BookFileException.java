package com.example.online_reader.book.files;

/** Fixed public failures; never expose filesystem, parser or client-supplied exception text. */
public final class BookFileException extends RuntimeException {
    private final int status;
    public BookFileException(int status, String message) { super(message); this.status=status; }
    public int status() { return status; }
    public static BookFileException invalid() { return new BookFileException(422,"Файл повреждён или не соответствует поддерживаемому формату."); }
    public static BookFileException busy() { return new BookFileException(503,"Загрузка временно недоступна. Повторите позже."); }
}
