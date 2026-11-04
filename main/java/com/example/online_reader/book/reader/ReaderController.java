package com.example.online_reader.book.reader;

import com.example.online_reader.book.service.BookService;
import com.example.online_reader.book.progress.*;
import com.example.online_reader.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.servlet.ModelAndView;
import java.io.IOException;
import java.util.Map;

@Controller
@RequestMapping("/library/books/{id}")
public class ReaderController {
    private final BookReader reader;
    private final BookService books;
    private final ProgressService progress;private final RangeProof proofs;private final CurrentAccount account;
    public ReaderController(BookReader reader,BookService books,ProgressService progress,RangeProof proofs,CurrentAccount account){this.reader=reader;this.books=books;this.progress=progress;this.proofs=proofs;this.account=account;}
    @GetMapping("/read")
    ModelAndView read(@PathVariable Long id,@RequestParam(required=false) String chapter,
                      @RequestParam(required=false) Integer offset,@RequestParam(required=false) String fingerprint,
                      @RequestParam(required=false) Integer version,@RequestParam(defaultValue="normal") BookReader.Font font,
                      @RequestParam(required=false) String anchor) {
        if(id<1)throw new BookReader.Failure(400);
        var saved=progress.load(id);
        if(chapter==null && offset==null && fingerprint==null && version==null && anchor==null && saved.position()!=null) {
            var position=saved.position();chapter=position.chapter();offset=position.offset();fingerprint=position.fingerprint();version=position.version();
        }
        var page=reader.read(id,chapter,offset==null?0:offset,fingerprint,version,font,anchor);
        var position=new ReadingPosition(page.chapter(),page.offset(),page.fingerprint(),BookReader.VERSION);
        String proof=proofs.issue(account.requireId(),id,position,page.offset(),page.next());
        return new ModelAndView("library/reader",Map.of("page",page,"book",books.detail(id),"progress",saved,"proof",proof));
    }
    @GetMapping("/resources/{index}")
    ResponseEntity<?> image(@PathVariable Long id,@PathVariable int index,@RequestParam(required=false) String fingerprint,
                            @RequestParam(required=false) Integer version,HttpServletRequest request)throws IOException {
        if(id<1)throw new BookReader.Failure(400);
        var image=reader.image(id,index,fingerprint,version);
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(image.media()));
        headers.setContentLength(image.length());headers.setContentDisposition(ContentDisposition.inline().build());
        headers.setCacheControl(CacheControl.noStore());
        if(request.getMethod().equals("HEAD")){image.close();return ResponseEntity.ok().headers(headers).build();}
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(image.stream()));
    }
    @ExceptionHandler(BookReader.Failure.class)
    ModelAndView failure(BookReader.Failure failure,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        if(failure.status()==503)response.setHeader("Retry-After","2");
        String message=switch(failure.status()) {
            case 400->"Некорректная позиция чтения. Откройте книгу заново из библиотеки.";
            case 404->"Книга или изображение недоступны. Проверьте библиотеку.";
            case 409->"Позиция относится к другой версии книги. Откройте книгу заново из библиотеки.";
            case 503->"Читалка занята. Повторите попытку через несколько секунд.";
            default->"Не удалось прочитать книгу. Исходный файл повреждён или не поддерживается.";
        };
        return new ModelAndView("library/reader-error",Map.of("readerError",message),HttpStatus.valueOf(failure.status()));
    }
}
