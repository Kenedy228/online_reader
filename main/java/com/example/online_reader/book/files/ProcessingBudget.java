package com.example.online_reader.book.files;

import java.io.*;
import java.time.Duration;

final class ProcessingBudget {
    private final long deadline;
    private volatile boolean cancelled;
    void cancel(){cancelled=true;}
    long remainingMillis(){return Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    ProcessingBudget() { this(Duration.ofSeconds(10)); }
    ProcessingBudget(Duration duration) { deadline=System.nanoTime()+duration.toNanos(); }
    void check() {
        if (cancelled || System.nanoTime()-deadline>=0 || Thread.currentThread().isInterrupted())
            throw new BookFileException(422,"Обработка книги превысила допустимое время.");
    }
    InputStream limit(InputStream input,long maximum) {
        return new FilterInputStream(input) {
            long count;
            @Override public int read() throws IOException {
                check();int value=super.read();if(value>=0) increment(1);return value;
            }
            @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                check();int n=in.read(buffer,offset,length);if(n>0)increment(n);return n;
            }
            void increment(int n) {check();count+=n;if(count>maximum)throw BookFileException.invalid();}
        };
    }
}
