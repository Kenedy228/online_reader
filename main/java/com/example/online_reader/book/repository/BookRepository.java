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
    Page<Book> findByOwner_Id(Integer owner, Pageable page);
    Optional<Book> findByIdAndOwner_Id(Long id, Integer owner);

    // Scope the lock itself to the owner; never load a foreign row then decide whether to mutate it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id and b.owner.id = :owner")
    Optional<Book> lockOwned(Long id, Integer owner);
}
