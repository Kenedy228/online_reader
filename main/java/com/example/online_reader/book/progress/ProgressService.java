package com.example.online_reader.book.progress;

import com.example.online_reader.book.files.FileBookTransactions;
import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.reader.BookReader;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.security.CurrentAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(timeout=5)
public class ProgressService {
    public record Snapshot(int revision,ReadingPosition position) { }
    public static final class Failure extends RuntimeException {
        private final int status;private final String code;
        public Failure(int status,String code){super(code);this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }
    private final ProgressRepository progress;
    private final CurrentAccount account;private final FileBookTransactions files;private final RangeProof proofs;
    public ProgressService(ProgressRepository progress,CurrentAccount account,FileBookTransactions files,RangeProof proofs){this.progress=progress;this.account=account;this.files=files;this.proofs=proofs;}
    @Transactional(readOnly=true,timeout=5)
    public Snapshot load(Long book) {
        var source=files.download(book);
        var result=progress.findByBookIdAndOwnerId(book,account.requireId()).map(p->new Snapshot(p.revision(),p.position())).orElse(new Snapshot(0,null));
        return result;
    }
    public Snapshot save(Long book,ReadingPosition position,int revision,String proof) {
        var source=files.lockReady(book);Integer owner=account.requireId();
        account.requireId();
        if(!source.fingerprint().equals(position.fingerprint()) || position.version()!=BookReader.VERSION)throw new Failure(409,"stale_position");
        if(!proofs.valid(owner,book,position,proof))throw new Failure(409,"stale_reader");
        int changed=revision==0?progress.create(book,owner,position.chapter(),position.offset(),position.fingerprint(),position.version())
                :progress.replace(book,owner,position.chapter(),position.offset(),position.fingerprint(),position.version(),revision);
        if(changed!=1)throw new Failure(409,"progress_conflict");
        return new Snapshot(revision+1,position);
    }
}
