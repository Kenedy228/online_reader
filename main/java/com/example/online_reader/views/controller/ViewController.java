package com.example.online_reader.views.controller;


import com.example.online_reader.user.DTO.UserLoginDTO;
import com.example.online_reader.views.service.IndexService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class ViewController {

    private final IndexService indexService;

    public ViewController(IndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping("/")
    public String index(Model model) {
        indexService.setUp(model);
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        // A fresh form must not bind credentials from the query string.
        model.addAttribute("user", new UserLoginDTO());
        model.addAttribute("pageTitle", "Вход в аккаунт");
        return "login";
    }

    @GetMapping("/registration")
    public String registration(Model model) {
        model.addAttribute("pageTitle", "Регистрация аккаунта");
        return "registration";
    }

    @PostMapping("/login")
    public String login(@Valid @ModelAttribute("user") UserLoginDTO userLoginDTO, BindingResult bindingResult, Model model) {
        userLoginDTO.setPassword(null);
        if (bindingResult.hasErrors()) {
            var viewErrors = new BeanPropertyBindingResult(userLoginDTO, "user");
            for (var error : bindingResult.getAllErrors()) {
                if (error instanceof FieldError field && "password".equals(field.getField())) {
                    // Clearing the bean alone leaves the original password in rejectedValue.
                    viewErrors.addError(new FieldError(field.getObjectName(), field.getField(), null,
                            field.isBindingFailure(), field.getCodes(), field.getArguments(),
                            field.getDefaultMessage()));
                } else {
                    viewErrors.addError(error);
                }
            }
            model.addAllAttributes(viewErrors.getModel());
            model.addAttribute("pageTitle", "Вход в аккаунт");
            return "login";
        }

        // Authentication is not implemented here; Spring Security login belongs to #14.
        return "redirect:/";
    }
}
