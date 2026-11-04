package com.example.online_reader.book.files;

import com.example.online_reader.security.AccountPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Locale;

/** Before CSRF: reject unauthenticated/body-token uploads without causing multipart parsing. */
public final class UploadAdmissionFilter extends OncePerRequestFilter {
    static final String JOB=UploadAdmissionFilter.class.getName()+".job", BUDGET=JOB+".budget";
    static final String ARRIVAL=JOB+".arrival";
    private final PrivateBookStorage storage;
    public UploadAdmissionFilter(PrivateBookStorage storage){this.storage=storage;}
    static boolean upload(HttpServletRequest request){return request.getMethod().equals("POST") && request.getServletPath().equals("/library/upload");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String type=request.getContentType();boolean multipart=type!=null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
        if(!upload(request)) {
            if(multipart){response.sendError(415);return;}
            chain.doFilter(request,response);return;
        }
        if(!multipart || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")){response.sendError(415);return;}
        request.setAttribute(ARRIVAL,System.nanoTime());
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated()) {
            String accept=request.getHeader("Accept");
            if(accept!=null && accept.contains("json"))response.sendError(401);
            else response.sendRedirect(request.getContextPath()+"/login");
            return;
        }
        if(!(authentication.getPrincipal() instanceof AccountPrincipal)
                || authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_USER"))){response.sendError(403);return;}
        // Live account state is checked in the bounded processing stage before reserving a book.
        String csrf=request.getHeader("X-CSRF-TOKEN");
        if(csrf==null || csrf.length()>1024){response.sendError(403);return;}
        if(request.getContentLengthLong()>11L*1024*1024){response.sendError(413);return;}
        PrivateBookStorage.Job job;
        try{job=storage.admit();}catch(BookFileException | IOException e){response.setHeader("Retry-After","10");response.sendError(503);return;}
        request.setAttribute(JOB,job);
        try{chain.doFilter(request,response);}finally{if(!request.isAsyncStarted())job.close();}
    }
}
