package com.example.online_reader.book.notes;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<BookNote,Long> {
    Optional<BookNote> findByIdAndBookIdAndOwnerId(Long id,Long book,Integer owner);
    Page<BookNote> findByBookIdAndOwnerId(Long book,Integer owner,Pageable page);
    long countByBookIdAndOwnerId(Long book,Integer owner);
    @Modifying @Query("delete from BookNote n where n.bookId=:book and n.ownerId=:owner")
    int deleteOwned(Long book,Integer owner);
}
