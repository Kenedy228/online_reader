package com.example.online_reader.book.files;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.file.*;

/** After native CSRF and URL authorization. Nonblocking read enforces a total receive deadline. */
public final class UploadReceiveFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(!UploadAdmissionFilter.upload(request)){chain.doFilter(request,response);return;}
        var job=(PrivateBookStorage.Job)request.getAttribute(UploadAdmissionFilter.JOB);
        if(job==null){response.sendError(403);return;}
        var async=request.startAsync();
        long arrival=(Long)request.getAttribute(UploadAdmissionFilter.ARRIVAL);
        async.setTimeout(Math.max(1,30000-java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-arrival)));
        try {
            var receiver=new Receiver(async,request,response,job);
            async.addListener(receiver);request.getInputStream().setReadListener(receiver);
        } catch(IOException | RuntimeException e){job.close();response.sendError(503);async.complete();}
    }
    private static final class Receiver implements ReadListener,AsyncListener {
        final AsyncContext async;final HttpServletRequest request;final HttpServletResponse response;
        final PrivateBookStorage.Job job;final OutputStream output;final ServletInputStream input;
        long received;boolean done;
        Receiver(AsyncContext async,HttpServletRequest request,HttpServletResponse response,PrivateBookStorage.Job job)throws IOException {
            this.async=async;this.request=request;this.response=response;this.job=job;
            input=request.getInputStream();output=Files.newOutputStream(job.request(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }
        @Override public synchronized void onDataAvailable()throws IOException {
            byte[] buffer=new byte[8192];
            try {
                while(!done && input.isReady() && !input.isFinished()) {
                    if(System.nanoTime()-(Long)request.getAttribute(UploadAdmissionFilter.ARRIVAL)>30_000_000_000L){fail(408);return;}
                    int n=input.read(buffer);if(n<0)break;received+=n;
                    if(received>11L*1024*1024){fail(413);return;}
                    try{output.write(buffer,0,n);}catch(IOException storageFailure){fail(503);return;}
                }
            }catch(IOException e){fail(400);}
        }
        @Override public synchronized void onAllDataRead()throws IOException {
            if(done)return;
            try {
                output.close();request.setAttribute(UploadAdmissionFilter.BUDGET,new ProcessingBudget());
                async.dispatch();done=true;
            }catch(IOException | RuntimeException failure){fail(503);}
        }
        private void fail(int status)throws IOException {
            if(done)return;done=true;
            // A failed close must not skip the rejection and accidentally complete with status200.
            try{output.close();}catch(IOException closeFailure){/* Job cleanup retains/counts any remaining file. */}
            try{if(!response.isCommitted()){if(status==503)response.setHeader("Retry-After","10");response.sendError(status);}}finally{async.complete();}
        }
        @Override public synchronized void onError(Throwable error){
            // The connection may already be unwritable; completing still releases private resources.
            try{done=true;output.close();}catch(IOException ignored){ }
            // Tomcat owns the ERROR/complete transition here. Calling complete again can strand
            // the request in COMPLETING and prevent graceful shutdown after a client abort.
        }
        @Override public synchronized void onTimeout(AsyncEvent event)throws IOException{fail(408);}
        @Override public synchronized void onError(AsyncEvent event)throws IOException{done=true;try{output.close();}finally{job.close();}}
        @Override public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}
        @Override public synchronized void onComplete(AsyncEvent event)throws IOException{try{output.close();}finally{job.close();}}
    }
}
