package com.example.online_reader.book.reader;

import java.io.*;

/** Independent, finite budget for each source read; no operator paths appear in failures. */
final class ReaderBudget {
    private final long deadline=System.nanoTime()+10_000_000_000L;
    void check()throws IOException {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new InterruptedIOException("Reader deadline");
    }
    InputStream limit(InputStream input,long maximum) {
        return new FilterInputStream(input) {
            long count;
            private void add(int n)throws IOException{check();if(n>0 && (count+=n)>maximum)throw new IOException("Reader limit");}
            public int read()throws IOException{check();int n=super.read();add(n<0?0:1);return n;}
            public int read(byte[] bytes,int offset,int length)throws IOException{check();int n=in.read(bytes,offset,length);add(n);return n;}
            public long skip(long n)throws IOException {if(n<=0)return 0;int length=(int)Math.min(n,8192);return Math.max(0,read(new byte[length],0,length));}
        };
    }
}
