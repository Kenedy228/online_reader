package com.example.online_reader.book.model;

import com.example.online_reader.user.model.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "books", uniqueConstraints = @UniqueConstraint(name = "books_owner_source_key", columnNames = {"owner_id", "source_sha256"}),
        indexes = @Index(name = "books_owner_created_idx", columnList = "owner_id, created_at DESC, id DESC"))
public class Book {
    public enum Format { EPUB, TXT }
    public enum State { PROCESSING, READY, DELETING }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 200) private String author;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8, updatable = false) private Format format;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private State state;
    @Column(nullable = false, updatable = false) private long originalSize;
    @Column(nullable = false, length = 64, updatable = false) private String sourceSha256;
    @Column(nullable = false, unique = true, updatable = false) private UUID storageKey;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Version @Column(nullable = false) private long version;

    protected Book() { }

    // Trusted ingestion only. There is deliberately no HTTP endpoint creating READY books.
    public static Book processing(User owner, String title, String author, Format format,
                                  long size, String sha256, UUID storageKey) {
        if (owner == null || owner.getId() == null || size < 1 || size > 10 * 1024 * 1024
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid book source");
        var book = new Book();
        book.owner = owner;
        book.editMetadata(title, author);
        book.format = Objects.requireNonNull(format);
        book.originalSize = size;
        book.sourceSha256 = sha256;
        book.storageKey = Objects.requireNonNull(storageKey);
        book.createdAt = Instant.now();
        book.state = State.PROCESSING;
        return book;
    }

    public void markReady() {
        if (state != State.PROCESSING) throw new IllegalStateException("Book is not processing");
        state = State.READY;
    }
    public void markDeleting() { state = State.DELETING; }
    public void editMetadata(String title, String author) {
        if (title == null || title.isBlank() || !validText(title) || author == null || !validText(author))
            throw new IllegalArgumentException("Invalid book metadata");
        this.title = title.strip();
        this.author = author.strip();
    }
    private static boolean validText(String text) {
        return text.codePointCount(0, text.length()) <= 200 && text.codePoints().noneMatch(Character::isISOControl);
    }
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public Format getFormat() { return format; }
    public State getState() { return state; }
    public long getOriginalSize() { return originalSize; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
