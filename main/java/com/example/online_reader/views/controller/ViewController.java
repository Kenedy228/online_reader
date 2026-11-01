package com.example.online_reader.views.controller;


import com.example.online_reader.user.DTO.UserLoginDTO;
import com.example.online_reader.views.service.IndexService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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
    public String login(UserLoginDTO userLoginDTO, Model model) {
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
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Вход в аккаунт");
            return "login";
        }

        return "redirect:/index";
    }
}
