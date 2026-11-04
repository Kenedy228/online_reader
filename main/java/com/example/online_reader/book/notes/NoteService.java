package com.example.online_reader.book.notes;

import com.example.online_reader.book.files.FileBookTransactions;
import com.example.online_reader.book.progress.*;
import com.example.online_reader.book.reader.BookReader;
import com.example.online_reader.security.CurrentAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly=true,timeout=5)
public class NoteService {
    public record View(Long id,String text,ReadingPosition position,int version,String created,boolean current) {
        public String preview(){return text.codePoints().limit(200).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString()+(text.codePointCount(0,text.length())>200?"…":"");}
    }
    public static class Failure extends RuntimeException {
        private final String code;public Failure(String code){super(code);this.code=code;}public String code(){return code;}
    }
    private final NoteRepository notes;private final FileBookTransactions files;private final CurrentAccount account;private final RangeProof proofs;
    public NoteService(NoteRepository notes,FileBookTransactions files,CurrentAccount account,RangeProof proofs){this.notes=notes;this.files=files;this.account=account;this.proofs=proofs;}
    public Page<View> list(Long book,int page,int size) {
        var source=files.download(book);Integer owner=account.requireId();
        if(page<0 || page>10000 || size<1 || size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result=notes.findByBookIdAndOwnerId(book,owner,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"))));
        if(page>0 && result.isEmpty())throw missing();return result.map(n->view(n,source));
    }
    public View get(Long book,Long note) {
        var source=files.download(book);return view(owned(book,note),source);
    }
    @Transactional(timeout=5)
    public Long create(Long book,String text,ReadingPosition position,String proof) {
        var source=files.lockReady(book);Integer owner=account.requireId();
        String normalized=NoteText.normalize(text);
        if(position==null || position.chapter()==null || position.offset()==null || position.fingerprint()==null || position.version()==null
                || !source.fingerprint().equals(position.fingerprint()) || position.version()!=BookReader.VERSION || !proofs.valid(owner,book,position,proof))throw new Failure("stale_position");
        if(notes.countByBookIdAndOwnerId(book,owner)>=200)throw new Failure("note_limit");
        return notes.saveAndFlush(BookNote.at(book,owner,normalized,position)).getId();
    }
    @Transactional(timeout=5)
    public void edit(Long book,Long id,String text,int version) {
        files.lockReady(book);var note=owned(book,id);revision(note,version);note.edit(text);notes.flush();
    }
    @Transactional(timeout=5)
    public void delete(Long book,Long id,int version) {
        files.lockReady(book);var note=owned(book,id);revision(note,version);notes.delete(note);notes.flush();
    }
    private BookNote owned(Long book,Long note){return notes.findByIdAndBookIdAndOwnerId(note,book,account.requireId()).orElseThrow(NoteService::missing);}
    private static void revision(BookNote note,int expected){if(expected<0 || expected==Integer.MAX_VALUE || note.getVersion()!=expected)throw new Failure("note_conflict");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND);}
    private static View view(BookNote note,FileBookTransactions.Source source) {
        var position=note.position();boolean current=position.fingerprint().equals(source.fingerprint()) && position.version()==BookReader.VERSION;
        return new View(note.getId(),note.getText(),position,note.getVersion(),DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withZone(ZoneOffset.UTC).format(note.getCreatedAt()),current);
    }
}
