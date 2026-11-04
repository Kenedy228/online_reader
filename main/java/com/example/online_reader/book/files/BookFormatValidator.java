package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

@Component
public class BookFormatValidator {
    public void validate(Path source,Book.Format format) { validate(source,format,new ProcessingBudget()); }
    void validate(Path source,Book.Format format,ProcessingBudget budget) {
        try {
            long size=Files.size(source);
            if(size<1 || size>10*1024*1024)throw BookFileException.invalid();
            if(format==Book.Format.TXT) text(source,budget);
            else new EpubValidator(budget).validate(source);
        } catch(BookFileException e){throw e;}
        catch(IOException | javax.xml.stream.XMLStreamException | IllegalArgumentException e){throw BookFileException.invalid();}
    }
    private void text(Path source,ProcessingBudget budget)throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        boolean first=true,content=false;
        try(var reader=new InputStreamReader(budget.limit(Files.newInputStream(source),10*1024*1024),decoder)) {
            char[] buffer=new char[8192];int n;
            while((n=reader.read(buffer))!=-1) {
                budget.check();
                for(int i=0;i<n;i++) {
                    char c=buffer[i];
                    if(first && c=='\ufeff'){first=false;continue;}
                    first=false;
                    if(Character.isISOControl(c) && c!='\t' && c!='\n' && c!='\r')throw BookFileException.invalid();
                    if(!Character.isWhitespace(c))content=true;
                }
            }
        }
        if(!content)throw BookFileException.invalid();
    }
}
