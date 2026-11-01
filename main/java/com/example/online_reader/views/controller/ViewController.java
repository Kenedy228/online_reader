package com.example.online_reader.views.controller;

import com.example.online_reader.views.service.IndexService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class ViewController {
    private final IndexService indexService;

    public ViewController(IndexService indexService) { this.indexService = indexService; }

    @GetMapping("/")
    public String index(Model model) {
        indexService.setUp(model);
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model, Principal principal, HttpServletRequest request) {
        if (principal != null) {
            return "redirect:/";
        }
        model.addAttribute("pageTitle", "Вход в аккаунт");
        model.addAttribute("loginError", request.getParameterMap().containsKey("error"));
        model.addAttribute("registered", request.getParameterMap().containsKey("registered"));
        model.addAttribute("loggedOut", request.getParameterMap().containsKey("logout"));
        return "login";
    }

}
