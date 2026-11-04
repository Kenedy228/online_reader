package com.example.online_reader.book.notes;

import com.example.online_reader.book.files.FileBookTransactions;
import com.example.online_reader.book.progress.*;
import com.example.online_reader.book.reader.BookReader;
import com.example.online_reader.book.service.BookService;
import com.example.online_reader.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/library/books/{bookId}/notes")
public class NoteController {
    private final NoteService notes;private final BookService books;private final FileBookTransactions files;
    private final BookReader reader;private final RangeProof proofs;private final CurrentAccount account;private final ProgressService progress;private final Validator validator;
    public NoteController(NoteService notes,BookService books,FileBookTransactions files,BookReader reader,RangeProof proofs,CurrentAccount account,ProgressService progress,Validator validator){this.notes=notes;this.books=books;this.files=files;this.reader=reader;this.proofs=proofs;this.account=account;this.progress=progress;this.validator=validator;}
    @InitBinder("note") void createBinder(WebDataBinder binder){formBinder(binder,"text","chapter","offset","fingerprint","version","proof","_csrf");}
    @InitBinder("edit") void editBinder(WebDataBinder binder){formBinder(binder,"text","version","_csrf");}
    @InitBinder("deletion") void deleteBinder(WebDataBinder binder){formBinder(binder,"version","confirmed","_csrf");}
    @GetMapping String list(@PathVariable Long bookId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Model model) {
        positive(bookId);model.addAttribute("notes",notes.list(bookId,page,size));model.addAttribute("book",books.detail(bookId));return "notes/list";
    }
    @GetMapping("/new") String create(@PathVariable Long bookId,@RequestParam(required=false) String chapter,@RequestParam(required=false) Integer offset,
            @RequestParam(required=false) String fingerprint,@RequestParam(required=false) Integer version,@RequestParam(required=false) String proof,Model model) {
        base(bookId,model);var form=new NoteForm();
        if(chapter==null && offset==null && fingerprint==null && version==null && proof==null) {
            var saved=progress.load(bookId).position();
            var page=reader.read(bookId,saved==null?null:saved.chapter(),saved==null?0:saved.offset(),saved==null?null:saved.fingerprint(),saved==null?null:saved.version(),BookReader.Font.normal,null);
            form.position(new ReadingPosition(page.chapter(),page.offset(),page.fingerprint(),BookReader.VERSION),proofs.issue(account.requireId(),bookId,new ReadingPosition(page.chapter(),page.offset(),page.fingerprint(),BookReader.VERSION),page.offset(),page.next()));
        }else {form.position(new ReadingPosition(chapter,offset,fingerprint,version),proof);position(form);prepare(bookId,form);}
        model.addAttribute("note",form);return "notes/new";
    }
    @PostMapping Object create(@PathVariable Long bookId,@Valid @ModelAttribute("note") NoteForm form,BindingResult errors,Model model,HttpServletResponse response) {
        base(bookId,model);allowed(errors);position(form);
        if(errors.getFieldErrors().stream().anyMatch(e->!e.getField().equals("text")))throw bad();
        text(form.getText(),errors);if(errors.hasErrors()){form.setText(preview(form.getText(),errors));return "notes/new";}
        try{return redirect(bookId,notes.create(bookId,form.getText(),form.position(),form.getProof()));}
        catch(NoteService.Failure failure) {
            response.setStatus(409);
            if(failure.code().equals("note_limit"))errors.reject("limit","На книгу можно сохранить не больше 200 заметок. Удалите ненужную и повторите сохранение.");
            else {
                errors.reject("position","Позиция требует повторной проверки. Текст не сохранён; проверьте сообщение и повторите сохранение.");
                try{prepare(bookId,form);}catch(BookReader.Failure unavailable){response.setStatus(unavailable.status());errors.reject("unavailable","Позиция недоступна. Текст оставлен в форме; вернитесь в читалку и проверьте книгу.");}
            }
            return "notes/new";
        }
    }
    @GetMapping("/{noteId}") String detail(@PathVariable Long bookId,@PathVariable Long noteId,Model model) {
        positive(bookId);positive(noteId);model.addAttribute("note",notes.get(bookId,noteId));model.addAttribute("book",books.detail(bookId));return "notes/detail";
    }
    @GetMapping("/{noteId}/edit") String edit(@PathVariable Long bookId,@PathVariable Long noteId,Model model) {
        positive(bookId);positive(noteId);var current=notes.get(bookId,noteId);var form=new NoteEditForm();form.setText(current.text());form.setVersion(current.version());
        model.addAttribute("current",current);model.addAttribute("edit",form);model.addAttribute("book",books.detail(bookId));return "notes/edit";
    }
    @PostMapping("/{noteId}/edit") Object edit(@PathVariable Long bookId,@PathVariable Long noteId,@Valid @ModelAttribute("edit") NoteEditForm form,BindingResult errors,Model model,HttpServletResponse response) {
        positive(bookId);positive(noteId);var current=notes.get(bookId,noteId);allowed(errors);model.addAttribute("current",current);model.addAttribute("book",books.detail(bookId));text(form.getText(),errors);
        if(!errors.hasErrors()) {
            try{notes.edit(bookId,noteId,form.getText(),form.getVersion());return redirect(bookId,noteId);}
            catch(NoteService.Failure failure){response.setStatus(409);current=notes.get(bookId,noteId);model.addAttribute("current",current);form.setVersion(current.version());errors.reject("conflict","Заметка изменена в другой вкладке. Сравните сохранённый текст ниже; повторное сохранение заменит его вашим.");}
        }
        form.setText(preview(form.getText(),errors));return "notes/edit";
    }
    @GetMapping("/{noteId}/delete") String delete(@PathVariable Long bookId,@PathVariable Long noteId,Model model) {
        positive(bookId);positive(noteId);var current=notes.get(bookId,noteId);var form=new NoteDeleteForm();form.setVersion(current.version());
        model.addAttribute("current",current);model.addAttribute("deletion",form);model.addAttribute("book",books.detail(bookId));return "notes/delete";
    }
    @PostMapping("/{noteId}/delete") Object delete(@PathVariable Long bookId,@PathVariable Long noteId,@Valid @ModelAttribute("deletion") NoteDeleteForm form,BindingResult errors,Model model,HttpServletResponse response) {
        positive(bookId);positive(noteId);var current=notes.get(bookId,noteId);allowed(errors);
        if(!errors.hasErrors()) {
            try{notes.delete(bookId,noteId,form.getVersion());return redirect(bookId,null);}
            catch(NoteService.Failure failure){response.setStatus(409);current=notes.get(bookId,noteId);form.setVersion(current.version());form.setConfirmed(false);errors.reject("conflict","Заметка изменилась. Проверьте сохранённый текст и подтвердите удаление заново.");}
        }
        model.addAttribute("current",current);model.addAttribute("book",books.detail(bookId));return "notes/delete";
    }
    @ExceptionHandler(BookReader.Failure.class)
    org.springframework.web.servlet.ModelAndView readerFailure(BookReader.Failure failure,HttpServletResponse response) {
        if(failure.status()==503)response.setHeader("Retry-After","2");
        return new org.springframework.web.servlet.ModelAndView("library/reader-error",java.util.Map.of("readerError","Не удалось проверить место заметки. Откройте книгу в читалке и повторите попытку."),HttpStatus.valueOf(failure.status()));
    }
    private void base(Long book,Model model){positive(book);files.download(book);model.addAttribute("book",books.detail(book));}
    private void position(NoteForm form){if(!validator.validate(form.position()).isEmpty() || form.getProof()==null || form.getProof().length()>100)throw bad();}
    private void prepare(Long book,NoteForm form) {
        var source=files.download(book);
        if(source.fingerprint().equals(form.getFingerprint()) && proofs.valid(account.requireId(),book,form.position(),form.getProof()))return;
        var page=reader.read(book,form.getChapter(),form.getOffset(),form.getFingerprint(),form.getVersion(),BookReader.Font.normal,null);
        form.setProof(proofs.issue(account.requireId(),book,form.position(),page.offset(),page.next()));
    }
    private static void formBinder(WebDataBinder binder,String... fields) {
        binder.setAllowedFields(fields);binder.setAutoGrowNestedPaths(false);
        // Browser headers are transport metadata, not alternate form values or suppressed input fields.
        if(binder instanceof org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder extended)extended.setHeaderPredicate(name->false);
    }
    private static void allowed(BindingResult errors){
        // Spring also offers URI variables to the binder. They have no form setters; @PathVariable stays authoritative.
        if(java.util.Arrays.stream(errors.getSuppressedFields()).anyMatch(field->!field.equals("bookId") && !field.equals("noteId")))throw bad();
    }
    private static void text(String text,BindingResult errors){try{NoteText.normalize(text);}catch(IllegalArgumentException e){if(!errors.hasFieldErrors("text"))errors.rejectValue("text","text","Напишите от 1 до 2000 Unicode-символов без управляющих символов; переносы строк и табуляция разрешены.");}}
    private static String preview(String text,BindingResult errors){if(text!=null && text.length()>8192)errors.reject("truncated","Ввод слишком большой: в форме показана только его часть. Сохранение отклонено; сократите текст до 2000 символов.");return text==null?"":text.substring(0,text.length()>8192 && Character.isHighSurrogate(text.charAt(8191))?8191:Math.min(8192,text.length())).codePoints().collect(StringBuilder::new,(b,cp)->b.appendCodePoint(cp>=0xd800 && cp<=0xdfff || Character.isISOControl(cp) && cp!='\n' && cp!='\r' && cp!='\t'?0xfffd:cp),StringBuilder::append).toString();}
    private static void positive(Long id){if(id<1)throw bad();}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    private static RedirectView redirect(Long book,Long note){var view=new RedirectView("/library/books/"+book+"/notes"+(note==null?"":"/"+note),true,false);view.setExposeModelAttributes(false);return view;}
}
