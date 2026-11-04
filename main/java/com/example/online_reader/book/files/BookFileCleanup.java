package com.example.online_reader.book.files;

import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.IOException;

@Component
public class BookFileCleanup {
    private final PrivateBookStorage storage;
    private final FileBookTransactions transactions;
    private long cursor;
    private volatile boolean started;
    public BookFileCleanup(PrivateBookStorage storage,FileBookTransactions transactions){this.storage=storage;this.transactions=transactions;}
    @EventListener(ApplicationReadyEvent.class)
    public void recover(){transactions.recoverInterrupted();started=true;retry();}
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public synchronized void retry() {
        if(!started)return;
        for(var source:transactions.abandoned())if(!storage.writing(source.key()))transactions.failed(source.id(),source.key());
        var batch=transactions.deleting(cursor);
        if(batch.isEmpty()){cursor=0;return;}
        for(var source:batch) {
            cursor=source.id();
            if(storage.writing(source.key()))continue;
            try {
                if(!storage.removeIfIdle(source.key()))continue;
                transactions.cleaned(source); // Never free the DB reservation before bytes are gone.
            } catch(IOException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Book cleanup retained a reservation for retry");
            }
        }
    }
}
