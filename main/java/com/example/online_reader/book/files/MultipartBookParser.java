package com.example.online_reader.book.files;

import com.example.online_reader.book.model.Book;
import org.apache.commons.fileupload.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class MultipartBookParser {
    record Parsed(String title,Book.Format format,long size,String hash) { }
    Parsed parse(PrivateBookStorage.Job job,String contentType,ProcessingBudget budget)throws IOException,FileUploadException {
        try(var input=budget.limit(Files.newInputStream(job.request()),11L*1024*1024)) {
            return parse(job,contentType,budget,input);
        }
    }
    private Parsed parse(PrivateBookStorage.Job job,String contentType,ProcessingBudget budget,InputStream input)throws IOException,FileUploadException {
        var upload=new FileUpload();upload.setHeaderEncoding("UTF-8");upload.setSizeMax(11L*1024*1024);
        upload.setFileSizeMax(10L*1024*1024);upload.setFileCountMax(2);upload.setPartHeaderSizeMax(2048);
        var context=new RequestContext() {
            public String getCharacterEncoding(){return "UTF-8";}
            public String getContentType(){return contentType;}
            public int getContentLength(){try{return Math.toIntExact(Files.size(job.request()));}catch(IOException e){return -1;}}
            public InputStream getInputStream(){return input;}
        };
        Parsed result=null;int count=0;
        var iterator=upload.getItemIterator(context);
        while(iterator.hasNext()) {
            budget.check();if(++count>2)throw BookFileException.invalid();
            var item=iterator.next();
            try(var stream=item.openStream()) {
                if(item.isFormField()) {
                    if(!"_csrf".equals(item.getFieldName()) || stream.readNBytes(513).length>512)throw BookFileException.invalid();
                    continue;
                }
                if(result!=null || !"file".equals(item.getFieldName()))throw BookFileException.invalid();
                String name=item.getName();
                if(name==null || name.isBlank() || name.codePointCount(0,name.length())>254 || name.contains("/") || name.contains("\\")
                        || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl))throw BookFileException.invalid();
                int dot=name.lastIndexOf('.');
                if(dot<1)throw new BookFileException(415,"Поддерживаются только EPUB и TXT в UTF-8.");
                Book.Format format=switch(name.substring(dot+1).toLowerCase(Locale.ROOT)){case "epub"->Book.Format.EPUB;case "txt"->Book.Format.TXT;default->throw new BookFileException(415,"Поддерживаются только EPUB и TXT в UTF-8.");};
                String title=name.substring(0,dot).strip();
                if(title.isBlank())throw BookFileException.invalid();
                if(title.codePointCount(0,title.length())>200)title=title.substring(0,title.offsetByCodePoints(0,200));
                MessageDigest digest;
                try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
                long size=0;
                try(var output=Files.newOutputStream(job.source(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    byte[] buffer=new byte[8192];int n;
                    while((n=stream.read(buffer))!=-1) {
                        budget.check();size+=n;if(size>10L*1024*1024)throw new BookFileException(413,"Размер файла не должен превышать 10 МиБ.");
                        digest.update(buffer,0,n);output.write(buffer,0,n);
                    }
                }
                if(size==0)throw BookFileException.invalid();
                result=new Parsed(title,format,size,HexFormat.of().formatHex(digest.digest()));
            }
        }
        if(result==null)throw BookFileException.invalid();return result;
    }
}
