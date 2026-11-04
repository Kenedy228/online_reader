package com.example.online_reader.book.progress;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="reading_progress")
public class ReadingProgress {
    @Id @Column(name="book_id") private Long bookId;
    @Column(name="owner_id",nullable=false) private Integer ownerId;
    @Column(nullable=false,length=8) private String chapter;
    @Column(name="position_offset",nullable=false) private int offset;
    @Column(nullable=false,length=64) private String fingerprint;
    @Column(name="representation_version",nullable=false) private int representationVersion;
    @Column(nullable=false) private int revision;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected ReadingProgress() { }
    public ReadingPosition position(){return new ReadingPosition(chapter,offset,fingerprint,representationVersion);}
    public int revision(){return revision;}
}
