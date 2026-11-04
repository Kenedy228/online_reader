package com.example.online_reader.book.notes;

import com.example.online_reader.book.progress.ReadingPosition;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="book_notes")
public class BookNote {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="book_id",nullable=false,updatable=false) private Long bookId;
    @Column(name="owner_id",nullable=false,updatable=false) private Integer ownerId;
    @Column(name="note_text",nullable=false,columnDefinition="text") private String text;
    @Column(nullable=false,length=8,updatable=false) private String chapter;
    @Column(name="position_offset",nullable=false,updatable=false) private int offset;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(name="representation_version",nullable=false,updatable=false) private int representationVersion;
    @Version @Column(nullable=false) private int version;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    protected BookNote() { }
    static BookNote at(Long book,Integer owner,String text,ReadingPosition position) {
        var note=new BookNote();note.bookId=book;note.ownerId=owner;note.edit(text);
        note.chapter=position.chapter();note.offset=position.offset();note.fingerprint=position.fingerprint();note.representationVersion=position.version();note.createdAt=Instant.now();return note;
    }
    void edit(String text){this.text=NoteText.normalize(text);}
    public Long getId(){return id;}
    public String getText(){return text;}
    public int getVersion(){return version;}
    public Instant getCreatedAt(){return createdAt;}
    public ReadingPosition position(){return new ReadingPosition(chapter,offset,fingerprint,representationVersion);}
}
