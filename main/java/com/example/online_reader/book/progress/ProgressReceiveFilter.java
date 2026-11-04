package com.example.online_reader.book.progress;

import com.example.online_reader.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.time.Duration;

/** Auth/CSRF precede a bounded nonblocking JSON receive; MVC only sees the complete small body. */
public final class ProgressReceiveFilter extends OncePerRequestFilter {
    private static final String BODY=ProgressReceiveFilter.class.getName()+".body";
    private final LoginAttemptLimiter limiter=new LoginAttemptLimiter(100,4096,Duration.ofMinutes(1));
    @Override protected boolean shouldNotFilterAsyncDispatch(){return false;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        if(!ProgressHttp.api(request) || !request.getMethod().equals("PUT")){chain.doFilter(request,response);return;}
        if(request.getAttribute(BODY) instanceof byte[] body){chain.doFilter(cached(request,body),response);return;}
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof AccountPrincipal principal)){ProgressHttp.write(response,403);return;}
        long retry=limiter.reserveAttempt(principal.getAccountId().toString());
        if(retry>0){response.setHeader("Retry-After",Long.toString(retry));ProgressHttp.write(response,429);return;}
        if(request.getContentType()==null || !request.getContentType().split(";",2)[0].strip().equalsIgnoreCase("application/json")){ProgressHttp.write(response,415);return;}
        if(request.getContentLengthLong()>2048){ProgressHttp.write(response,413);return;}
        var async=request.startAsync();async.setTimeout(5000);
        try{var receiver=new Receiver(async,request,response);async.addListener(receiver);request.getInputStream().setReadListener(receiver);}
        catch(IOException | RuntimeException e){try{ProgressHttp.write(response,503);}finally{async.complete();}}
    }
    private static HttpServletRequest cached(HttpServletRequest request,byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read(){return input.read();}
                    public int read(byte[] bytes,int offset,int length){return input.read(bytes,offset,length);}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new IllegalStateException("Body already received");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
            @Override public int getContentLength(){return body.length;}
            @Override public long getContentLengthLong(){return body.length;}
        };
    }
    private static final class Receiver implements ReadListener,AsyncListener {
        final AsyncContext async;final HttpServletRequest request;final HttpServletResponse response;final ServletInputStream input;
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream(2048);final long deadline=System.nanoTime()+5_000_000_000L;boolean done;
        Receiver(AsyncContext async,HttpServletRequest request,HttpServletResponse response)throws IOException{this.async=async;this.request=request;this.response=response;input=request.getInputStream();}
        public synchronized void onDataAvailable()throws IOException {
            byte[] buffer=new byte[512];
            try {
                while(!done && input.isReady() && !input.isFinished()) {
                    if(System.nanoTime()>deadline){fail(408);return;}
                    int n=input.read(buffer);if(n<0)break;
                    if(bytes.size()+n>2048){fail(413);return;}bytes.write(buffer,0,n);
                }
            }catch(IOException e){fail(400);}
        }
        public synchronized void onAllDataRead()throws IOException {
            if(done)return;
            if(System.nanoTime()>deadline){fail(408);return;}
            request.setAttribute(BODY,bytes.toByteArray());done=true;async.dispatch();
        }
        private void fail(int status)throws IOException {
            if(done)return;done=true;try{if(!response.isCommitted())ProgressHttp.write(response,status);}finally{async.complete();}
        }
        public synchronized void onError(Throwable error){done=true;} // Container owns ERROR/complete after an aborted read.
        public synchronized void onTimeout(AsyncEvent event)throws IOException{fail(408);}
        public synchronized void onError(AsyncEvent event){done=true;}
        public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}
        public void onComplete(AsyncEvent event){ }
    }
}
