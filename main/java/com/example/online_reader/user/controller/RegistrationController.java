package com.example.online_reader.user.controller;

import com.example.online_reader.user.DTO.RegistrationForm;
import com.example.online_reader.user.service.RegistrationService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Controller
public class RegistrationController {
    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) { this.registration = registration; }

    @InitBinder("registration")
    void bindRegistration(WebDataBinder binder) {
        binder.setAllowedFields("username", "email", "password", "confirmPassword");
        binder.setAutoGrowNestedPaths(false);
    }

    @GetMapping("/registration")
    public String form(Model model, Principal principal) {
        if (principal != null) return "redirect:/";
        model.addAttribute("registration", new RegistrationForm());
        return showForm(model);
    }

    @PostMapping("/registration")
    public String register(@Valid @ModelAttribute("registration") RegistrationForm form,
                           BindingResult errors, Model model, Principal principal, HttpServletRequest request) {
        try {
            if (principal != null) return "redirect:/";
            for (String field : new String[]{"username", "email", "password", "confirmPassword"}) {
                String[] values = request.getParameterValues(field);
                if (values != null && values.length != 1)
                    errors.rejectValue(field, "registration.repeated", "Передайте одно значение поля.");
            }
            if (!errors.hasErrors()) {
                if (registration.register(form)) return "redirect:/login?registered";
                String message = "Не удалось зарегистрировать аккаунт. Проверьте имя и email или войдите.";
                errors.rejectValue("username", "registration.conflict", message);
                errors.rejectValue("email", "registration.conflict", message);
            }
            // FieldError can retain rejected password values even after clearing the DTO.
            var safe = new BeanPropertyBindingResult(form, "registration");
            for (var error : errors.getAllErrors()) {
                if (error instanceof FieldError field) {
                    Object value = field.getField().equals("password") || field.getField().equals("confirmPassword")
                            ? null : field.getRejectedValue();
                    safe.addError(new FieldError("registration", field.getField(), value, field.isBindingFailure(),
                            field.getCodes(), field.getArguments(), field.getDefaultMessage()));
                } else safe.addError(error);
            }
            model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "registration", safe);
            return showForm(model);
        } finally {
            form.clearPasswords();
        }
    }

    private String showForm(Model model) {
        model.addAttribute("pageTitle", "Регистрация аккаунта");
        model.addAttribute("usernamePattern", RegistrationForm.USERNAME_PATTERN);
        return "registration";
    }
}
