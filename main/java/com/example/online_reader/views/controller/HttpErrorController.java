package com.example.online_reader.views.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.net.URI;
import java.util.Map;

/** Only the error status is trusted; never render exception/message/path/query/BindingResult attributes. */
@Controller
@RequestMapping("/error")
public class HttpErrorController implements ErrorController {
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView html(HttpServletRequest request) {
        var status = status(request);
        return new ModelAndView("error", Map.of("status", status.value(), "errorTitle", message(status)), status);
    }

    @RequestMapping
    public ResponseEntity<ProblemDetail> problem(HttpServletRequest request) {
        var status = status(request);
        var problem = ProblemDetail.forStatusAndDetail(status, message(status));
        problem.setInstance(URI.create("/error")); // Never copy the possibly sensitive original request URI.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static HttpStatus status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        var status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String message(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Некорректный запрос";
            case UNAUTHORIZED -> "Требуется вход в аккаунт";
            case FORBIDDEN -> "Доступ запрещён";
            case NOT_FOUND -> "Страница не найдена";
            case METHOD_NOT_ALLOWED -> "Метод не поддерживается";
            case PAYLOAD_TOO_LARGE -> "Запрос слишком большой";
            case UNSUPPORTED_MEDIA_TYPE -> "Тип данных не поддерживается";
            case TOO_MANY_REQUESTS -> "Слишком много запросов";
            default -> "Не удалось выполнить запрос";
        };
    }
}
