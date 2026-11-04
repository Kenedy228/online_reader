package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.security.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class BookFileFailureTest {
    @Autowired PrivateBookStorage storage;
    @Autowired FileBookTransactions transactions;
    @Autowired BookFileCleanup cleanup;
    @Autowired BookRepository books;
    @Autowired UserRepository users;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    User owner;
    @BeforeEach void identity() {
        owner=users.saveAndFlush(new User("failure-"+UUID.randomUUID(),encoder.encode("Synthetic-storage-password")));
        var principal=new AccountPrincipal(owner);principal.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    Long reserve(UUID key){return transactions.reserve("Recovery",Book.Format.TXT,7,UUID.randomUUID().toString().replace("-","").repeat(2),key);}
    @Test void interruptedProcessingIsHiddenAndItsPhysicalSourceIsRetriedBeforeFreeingQuota()throws Exception {
        UUID key=UUID.randomUUID();Long id=reserve(key);
        try(var job=storage.admit()) {
            Files.writeString(job.source(),"partial");storage.persist(key,job.source(),new ProcessingBudget());
        }
        // Simulate first startup after a crash: PROCESSING must not survive as readable content.
        cleanup.recover();for(int i=0;i<3;i++)cleanup.retry();
        assertThat(books.findById(id)).isEmpty();
        assertThatThrownBy(()->storage.open(key,7)).isInstanceOf(java.io.IOException.class);
    }
    @Test void writeFailureLeavesMarkerUntilCleanupAndDeletingCannotBecomeReady()throws Exception {
        UUID key=UUID.randomUUID();Long id=reserve(key);
        storage.beginWrite(key);
        try(var job=storage.admit()) {
            assertThatThrownBy(()->storage.persist(key,job.source(),new ProcessingBudget())).isInstanceOf(java.io.IOException.class);
            transactions.failed(id,key);cleanup.retry();
            assertThat(books.findById(id).orElseThrow().getState()).isEqualTo(Book.State.DELETING);
            assertThat(books.reservedBytes(owner.getId())).isEqualTo(7);
            assertThatThrownBy(()->transactions.ready(id,key,new ProcessingBudget())).isInstanceOf(BookFileException.class);
        }finally{storage.endWrite(key);}
        for(int i=0;i<3;i++)cleanup.retry();assertThat(books.findById(id)).isEmpty();
    }
    @Test void openDownloadRetainsQuotaUntilStreamCloses()throws Exception {
        UUID key=UUID.randomUUID();Long id=reserve(key);
        try(var job=storage.admit()) {
            Files.writeString(job.source(),"content");storage.persist(key,job.source(),new ProcessingBudget());
        }
        transactions.ready(id,key,new ProcessingBudget());
        try(var source=storage.open(key,7)) {
            transactions.failed(id,key);for(int i=0;i<3;i++)cleanup.retry();
            assertThat(books.findById(id)).isPresent();assertThat(books.reservedBytes(owner.getId())).isEqualTo(7);
            assertThat(new String(source.readAllBytes())).isEqualTo("content");
        }
        for(int i=0;i<3;i++)cleanup.retry();assertThat(books.findById(id)).isEmpty();
    }
}
