package com.example.online_reader.book.files;

import org.springframework.stereotype.Service;
import org.apache.commons.fileupload.FileUploadException;
import java.io.*;
import java.util.UUID;

@Service
public class BookFileService {
    private final PrivateBookStorage storage;
    private final FileBookTransactions transactions;
    private final BookFormatValidator validator;
    public BookFileService(PrivateBookStorage storage,FileBookTransactions transactions,BookFormatValidator validator){this.storage=storage;this.transactions=transactions;this.validator=validator;}
    public Long upload(PrivateBookStorage.Job job,String contentType,ProcessingBudget budget) {
        UUID key=UUID.randomUUID();Long id=null;
        storage.beginWrite(key);
        try {
            var parsed=new MultipartBookParser().parse(job,contentType,budget);
            id=transactions.reserve(parsed.title(),parsed.format(),parsed.size(),parsed.hash(),key);
            budget.check();
            validator.validate(job.source(),parsed.format(),budget);
            storage.persist(key,job.source(),budget);
            budget.check();transactions.ready(id,key,budget);budget.check();
            return id;
        } catch(IOException | FileUploadException e) {
            if(id!=null)transactions.failed(id,key);
            if(oversized(e) || oversized(e.getCause()))
                throw new BookFileException(413,"Размер файла не должен превышать 10 МиБ.");
            if(id!=null && e instanceof IOException)throw BookFileException.busy();
            throw BookFileException.invalid();
        } catch(RuntimeException e) {
            if(id!=null)transactions.failed(id,key);
            if(e instanceof org.springframework.dao.DataAccessException || e instanceof org.springframework.transaction.TransactionException)throw BookFileException.busy();
            throw e;
        } finally {storage.endWrite(key);}
    }
    private static boolean oversized(Throwable error) {
        return error instanceof org.apache.commons.fileupload.FileUploadBase.FileSizeLimitExceededException
                || error instanceof org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException;
    }
}
