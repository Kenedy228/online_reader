package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.security.CurrentAccount;
import com.example.online_reader.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** SQL transactions stay separate from filesystem I/O and from their callers' exception compensation. */
@Service
@Transactional(timeout=5)
public class FileBookTransactions {
    public record Source(Long id,UUID key,Book.Format format,long size) { }
    public static final class Duplicate extends RuntimeException {
        private final Long id;private final Book.State state;
        Duplicate(Book book){id=book.getId();state=book.getState();}
        public Long id(){return id;} public Book.State state(){return state;}
    }
    private final BookRepository books;
    private final UserRepository users;
    private final CurrentAccount account;
    private final BookStorageLease lease;
    private final javax.sql.DataSource dataSource;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
    public FileBookTransactions(BookRepository books,UserRepository users,CurrentAccount account,BookStorageLease lease,javax.sql.DataSource dataSource){this.books=books;this.users=users;this.account=account;this.lease=lease;this.dataSource=dataSource;}
    public Long reserve(String title,Book.Format format,long size,String hash,UUID key) {
        limits();
        Integer owner=account.requireId();
        var user=users.lockById(owner).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN));
        entities.refresh(user);account.requireId();
        // The owner row serializes count, bytes and dedup across concurrent requests.
        var existing=books.findByOwner_IdAndSourceSha256(owner,hash);
        if(existing.isPresent())throw new Duplicate(existing.get());
        if(books.countByOwner_Id(owner)>=100 || books.reservedBytes(owner)+size>1073741824L)
            throw new BookFileException(409,"Лимит библиотеки исчерпан. Удалите ненужные книги и дождитесь завершения удаления.");
        return books.saveAndFlush(Book.processing(user,title,"",format,size,hash,key)).getId();
    }
    public void ready(Long id,UUID key,ProcessingBudget budget) {
        limits();budget.check();
        var book=books.lockOwned(id,account.requireId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!book.getStorageKey().equals(key) || book.getState()!=Book.State.PROCESSING)throw new BookFileException(409,"Загрузка книги отменена.");
        budget.check();book.markReady();books.flush();budget.check();
    }
    // These are internal maintenance commands; a request can never choose a storage key.
    public void failed(Long id,UUID key) {limits();books.lockStored(id,key).ifPresent(Book::markDeleting);}
    @Transactional(readOnly=true,timeout=5)
    public Source download(Long id) {
        limits();
        var book=books.findByIdAndOwner_Id(id,account.requireId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(book.getState()!=Book.State.READY)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new Source(book.getId(),book.getStorageKey(),book.getFormat(),book.getOriginalSize());
    }
    public int recoverInterrupted(){limits();return books.recoverInterrupted();}
    @Transactional(readOnly=true,timeout=5)
    public List<Source> abandoned() {
        limits();
        return books.findByStateAndCreatedAtBefore(Book.State.PROCESSING,java.time.Instant.now().minusSeconds(60),PageRequest.of(0,50))
                .stream().map(b->new Source(b.getId(),b.getStorageKey(),b.getFormat(),b.getOriginalSize())).toList();
    }
    @Transactional(readOnly=true,timeout=5)
    public List<Source> deleting(long cursor) {
        limits();
        return books.findByStateAndIdGreaterThanOrderByIdAsc(Book.State.DELETING,cursor,PageRequest.of(0,50))
                .stream().map(b->new Source(b.getId(),b.getStorageKey(),b.getFormat(),b.getOriginalSize())).toList();
    }
    public void cleaned(Source source) {
        limits();
        books.lockStored(source.id,source.key).filter(b->b.getState()==Book.State.DELETING).ifPresent(books::delete);
    }
    private void limits() {
        lease.requireValid();
        try {
            var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try{connection.setNetworkTimeout(Runnable::run,2000);}
            finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,dataSource);}
            if(lease.postgres())entities.createNativeQuery("select set_config('lock_timeout','1000ms',true),set_config('statement_timeout','2000ms',true)").getSingleResult();
        }catch(java.sql.SQLException e){throw BookFileException.busy();}
    }

}
