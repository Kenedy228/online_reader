package com.example.online_reader.book.service;

import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.book.DTO.BookMetadataForm;
import com.example.online_reader.security.CurrentAccount;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
@Validated
@Transactional(readOnly = true)
public class BookService {
    public record BookView(Long id, String title, String author, Book.Format format, Book.State state,
                           long originalSize, String created, long version) {
        public String stateLabel() {
            return switch (state) { case PROCESSING -> "Обрабатывается"; case READY -> "Готова"; case DELETING -> "Удаляется"; };
        }
        public boolean ready() { return state == Book.State.READY; }
        public boolean deleting() { return state == Book.State.DELETING; }
    }
    private final BookRepository books;
    private final CurrentAccount account;
    private final com.example.online_reader.book.progress.ProgressRepository progress;
    private final com.example.online_reader.book.notes.NoteRepository notes;
    public BookService(BookRepository books, CurrentAccount account, com.example.online_reader.book.progress.ProgressRepository progress, com.example.online_reader.book.notes.NoteRepository notes) { this.books = books; this.account = account; this.progress=progress; this.notes=notes; }
    public Page<BookView> list(int page, int size) {
        Integer owner = account.requireId();
        if (page < 0 || page > 10000 || size < 1 || size > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result = books.findByOwner_Id(owner, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        if (page > 0 && result.isEmpty()) throw missing();
        return result.map(BookService::view);
    }
    public BookView detail(Long id) {
        var book = books.findByIdAndOwner_Id(id, account.requireId()).orElseThrow(BookService::missing);
        if (book.getState() == Book.State.DELETING) throw missing();
        return view(book);
    }
    @Transactional
    public void edit(Long id, @Valid BookMetadataForm form) {
        var book = ownedForWrite(id);
        if (book.getState() != Book.State.READY || book.getVersion() != form.getVersion()) throw conflict();
        book.editMetadata(form.getTitle(), form.getAuthor());
    }
    @Transactional
    public void delete(Long id, long version) {
        var book = books.lockOwned(id, account.requireId()).orElseThrow(BookService::missing);
        if (book.getState() == Book.State.DELETING) return;
        if (book.getVersion() != version) throw conflict();
        // Keep the source reference and quota reservation until physical cleanup (#22).
        notes.deleteOwned(id,account.requireId());
        progress.deleteOwned(id,account.requireId());
        book.markDeleting();
    }
    private Book ownedForWrite(Long id) {
        var book = books.lockOwned(id, account.requireId()).orElseThrow(BookService::missing);
        if (book.getState() == Book.State.DELETING) throw missing();
        return book;
    }
    public static class Conflict extends RuntimeException { }
    private static Conflict conflict() { return new Conflict(); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static BookView view(Book b) {
        return new BookView(b.getId(), b.getTitle(), b.getAuthor(), b.getFormat(), b.getState(), b.getOriginalSize(),
                DateTimeFormatter.ofPattern("dd.MM.uuuu").withZone(ZoneOffset.UTC).format(b.getCreatedAt()), b.getVersion());
    }
}
