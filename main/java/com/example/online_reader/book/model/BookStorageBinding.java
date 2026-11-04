package com.example.online_reader.book.model;

import jakarta.persistence.*;
import java.util.UUID;

/** The database and its source directory must be restored/moved together. */
@Entity @Table(name="book_storage")
public class BookStorageBinding {
    @Id private Integer id;
    @Column(nullable=false,updatable=false) private UUID rootId;
    protected BookStorageBinding() { }
}
