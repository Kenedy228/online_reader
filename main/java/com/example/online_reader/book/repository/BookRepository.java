package com.example.online_reader.book.repository;

import com.example.online_reader.book.model.Book;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {
    Optional<Book> findByOwner_IdAndSourceSha256(Integer owner, String hash);
    long countByOwner_Id(Integer owner);
    java.util.List<Book> findByStateAndCreatedAtBefore(Book.State state, java.time.Instant cutoff, Pageable page);
    @Query("select coalesce(sum(b.originalSize), 0) from Book b where b.owner.id = :owner")
    long reservedBytes(Integer owner);
    java.util.List<Book> findByStateAndIdGreaterThanOrderByIdAsc(Book.State state, Long cursor, Pageable page);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Book b set b.state = com.example.online_reader.book.model.Book.State.DELETING, b.version = b.version + 1 where b.state = com.example.online_reader.book.model.Book.State.PROCESSING")
    int recoverInterrupted();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id and b.storageKey = :key")
    Optional<Book> lockStored(Long id, java.util.UUID key);
    Page<Book> findByOwner_Id(Integer owner, Pageable page);
    Optional<Book> findByIdAndOwner_Id(Long id, Integer owner);

    // Scope the lock itself to the owner; never load a foreign row then decide whether to mutate it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id and b.owner.id = :owner")
    Optional<Book> lockOwned(Long id, Integer owner);
}
