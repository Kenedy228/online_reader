package com.example.online_reader.book.files;

import com.example.online_reader.security.CurrentAccount;
import com.example.online_reader.book.model.Book;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@Controller
@RequestMapping("/library")
public class BookFileController {
    private final BookFileService files;
    private final FileBookTransactions transactions;
    private final PrivateBookStorage storage;
    private final CurrentAccount account;
    private final java.util.concurrent.Executor workers;
    public BookFileController(BookFileService files,FileBookTransactions transactions,PrivateBookStorage storage,CurrentAccount account,@org.springframework.beans.factory.annotation.Qualifier("bookUploadWorkers") java.util.concurrent.Executor workers){this.files=files;this.transactions=transactions;this.storage=storage;this.account=account;this.workers=workers;}
    @GetMapping("/upload")
    String uploadForm(){account.requireId();return "library/upload";}
    @PostMapping("/upload")
    org.springframework.web.context.request.async.DeferredResult<org.springframework.web.servlet.ModelAndView> upload(HttpServletRequest request) {
        var job=(PrivateBookStorage.Job)request.getAttribute(UploadAdmissionFilter.JOB);
        var budget=(ProcessingBudget)request.getAttribute(UploadAdmissionFilter.BUDGET);
        if(job==null || budget==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result=new org.springframework.web.context.request.async.DeferredResult<org.springframework.web.servlet.ModelAndView>(budget.remainingMillis());
        var running=new java.util.concurrent.atomic.AtomicReference<Thread>();
        // Serialize cancellation with worker detachment: never interrupt a thread reused by another upload.
        Runnable cancel=()->{synchronized(running){budget.cancel();var thread=running.get();if(thread!=null)thread.interrupt();}};
        result.onTimeout(()->{cancel.run();result.setResult(error(422,"Не удалось вовремя получить результат. Проверьте библиотеку перед повторной попыткой."));});
        result.onError(failure->cancel.run());result.onCompletion(job::close);
        String contentType=request.getContentType();
        job.beginWork();
        try {
            workers.execute(new org.springframework.security.concurrent.DelegatingSecurityContextRunnable(()->{
                synchronized(running){running.set(Thread.currentThread());}
                try {
                    budget.check();Long id=files.upload(job,contentType,budget);
                    var redirect=new org.springframework.web.servlet.view.RedirectView("/library/books/"+id,true,false);
                    redirect.setExposeModelAttributes(false);
                    result.setResult(new org.springframework.web.servlet.ModelAndView(redirect));
                }catch(FileBookTransactions.Duplicate duplicate) {
                    var view=error(409,switch(duplicate.state()){case READY->"Эта книга уже есть в вашей библиотеке.";case PROCESSING->"Эта книга уже обрабатывается.";case DELETING->"Удаление этой книги ещё не завершено. Повторите загрузку позже.";});
                    if(duplicate.state()==Book.State.READY)view.addObject("duplicateId",duplicate.id());result.setResult(view);
                }catch(BookFileException failure){result.setResult(error(failure.status(),failure.getMessage()));}
                catch(org.springframework.security.access.AccessDeniedException failure){result.setErrorResult(new ResponseStatusException(HttpStatus.FORBIDDEN));}
                catch(RuntimeException failure){result.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));}
                finally{synchronized(running){running.set(null);Thread.interrupted();}job.finishWork();}
            }));
        }catch(java.util.concurrent.RejectedExecutionException failure){job.finishWork();result.setResult(error(503,"Загрузка временно недоступна. Повторите позже."));}
        return result;
    }
    private static org.springframework.web.servlet.ModelAndView error(int status,String message) {
        return new org.springframework.web.servlet.ModelAndView("library/upload",java.util.Map.of("uploadError",message),HttpStatus.valueOf(status));
    }
    @GetMapping("/books/{id}/download")
    ResponseEntity<?> download(@PathVariable Long id,HttpServletRequest request) {
        if(id<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var source=transactions.download(id); // Owner/state checked before any storage access.
        try {
            var input=storage.open(source.key(),source.size());
            var headers=new HttpHeaders();
            headers.setContentType(source.format()==Book.Format.TXT?new MediaType("text","plain",java.nio.charset.StandardCharsets.UTF_8):MediaType.parseMediaType("application/epub+zip"));
            headers.setContentDisposition(ContentDisposition.attachment().filename("book-"+id+"."+source.format().name().toLowerCase(java.util.Locale.ROOT)).build());
            headers.setContentLength(source.size());headers.setCacheControl(CacheControl.noStore());
            if(request.getMethod().equals("HEAD")){input.close();return ResponseEntity.ok().headers(headers).build();}
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(input));
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    }
}
