package com.example.online_reader.book.reader;

import java.io.*;

/** Canonical TXT positions are Unicode code points after BOM and newline normalization. */
public record TextFragment(String text,int total,int next) {
    public static TextFragment read(Reader source,int offset,int limit)throws IOException {
        if(offset<0 || limit<1 || limit>16384)throw new IllegalArgumentException("Invalid fragment range");
        var result=new StringBuilder();int position=0;boolean first=true,afterCr=false;
        try(var reader=new PushbackReader(source,1)) {
            int value;
            while((value=reader.read())!=-1) {
                if((position&1023)==0 && Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                char c=(char)value;
                if(first && c=='\ufeff'){first=false;continue;}first=false;
                if(afterCr && c=='\n'){afterCr=false;continue;}afterCr=false;
                int cp=c;
                if(c=='\r'){cp='\n';afterCr=true;}
                else if(Character.isHighSurrogate(c)) {
                    int low=reader.read();if(low<0 || !Character.isLowSurrogate((char)low))throw new IOException("Invalid Unicode");
                    cp=Character.toCodePoint(c,(char)low);
                }else if(Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\n' && c!='\t')throw new IOException("Invalid text");
                if(position>=offset && position<offset+limit)result.appendCodePoint(cp);
                if(++position>10485760)throw new IOException("Text limit");
            }
        }
        if(offset>position || offset==position && position>0)throw new IllegalArgumentException("Invalid fragment range");
        return new TextFragment(result.toString(),position,Math.min(position,offset+limit));
    }
}
