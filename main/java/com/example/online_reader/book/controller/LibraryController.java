package com.example.online_reader.book.controller;

import com.example.online_reader.book.DTO.BookDeleteForm;
import com.example.online_reader.book.DTO.BookMetadataForm;
import com.example.online_reader.book.service.BookService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/library")
public class LibraryController {
    private final BookService books;
    public LibraryController(BookService books) { this.books = books; }
    @InitBinder("metadata")
    void metadataBinder(WebDataBinder binder) {
        binder.setAllowedFields("title", "author", "version");
        binder.setAutoGrowNestedPaths(false);
    }
    @InitBinder("deletion")
    void deleteBinder(WebDataBinder binder) {
        binder.setAllowedFields("version", "confirmed");
        binder.setAutoGrowNestedPaths(false);
    }
    @GetMapping
    String list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Model model) {
        model.addAttribute("books", books.list(page, size));
        return "library/list";
    }
    @GetMapping("/books/{id}")
    String detail(@PathVariable Long id, Model model) {
        model.addAttribute("book", books.detail(positive(id)));
        return "library/detail";
    }
    @GetMapping("/books/{id}/edit")
    String edit(@PathVariable Long id, Model model) {
        var book = books.detail(positive(id));
        if (!book.ready()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var form = new BookMetadataForm();
        form.setTitle(book.title()); form.setAuthor(book.author()); form.setVersion(book.version());
        model.addAttribute("book", book);
        model.addAttribute("metadata", form);
        return "library/edit";
    }
    @PostMapping("/books/{id}/edit")
    String edit(@PathVariable Long id, @Valid @ModelAttribute("metadata") BookMetadataForm form,
                BindingResult errors, Model model, HttpServletResponse response) {
        var book = books.detail(positive(id)); // Check ownership even when submitted fields are invalid.
        model.addAttribute("book", book);
        if (!book.ready()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (errors.hasErrors()) return "library/edit";
        try { books.edit(id, form); }
        catch (BookService.Conflict conflict) {
            book = books.detail(id);
            model.addAttribute("book", book);
            form.setVersion(book.version());
            errors.reject("conflict", "Книга изменилась. Сравните данные ниже с вашими правками и сохраните ещё раз.");
            response.setStatus(409);
            return "library/edit";
        }
        return "redirect:/library/books/" + id;
    }
    @GetMapping("/books/{id}/delete")
    String delete(@PathVariable Long id, Model model) {
        var book = books.detail(positive(id));
        var form = new BookDeleteForm(); form.setVersion(book.version());
        model.addAttribute("book", book); model.addAttribute("deletion", form);
        return "library/delete";
    }
    @PostMapping("/books/{id}/delete")
    String delete(@PathVariable Long id, @Valid @ModelAttribute("deletion") BookDeleteForm form,
                  BindingResult errors, Model model, HttpServletResponse response) {
        positive(id);
        // Repeated confirmed POST is safe even after the first request hid the card.
        if (!errors.hasErrors()) {
            try { books.delete(id, form.getVersion()); return "redirect:/library"; }
            catch (BookService.Conflict conflict) {
                errors.reject("conflict", "Книга изменилась. Проверьте данные и подтвердите удаление ещё раз.");
                response.setStatus(409);
            }
        }
        var book = books.detail(id);
        model.addAttribute("book", book);
        if (response.getStatus() == 409) { form.setVersion(book.version()); form.setConfirmed(false); }
        return "library/delete";
    }
    private static Long positive(Long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return id;
    }
}
