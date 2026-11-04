package com.example.online_reader.book.progress;

import jakarta.servlet.http.*;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import org.springframework.http.*;

/** Fixed API errors; never reflect request, exception, credentials, or arbitrary codes. */
public final class ProgressHttp {
    private ProgressHttp() { }
    public record Error(String code,String message) { }
    public static boolean api(HttpServletRequest request) {
        Object original=request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path=original instanceof String value?value:request.getRequestURI();
        return path.startsWith(request.getContextPath()) && path.substring(request.getContextPath().length()).matches("/api/v1/books/[^/]+/reading-progress");
    }
    public static Error error(int status,String code) {
        return switch(code==null?"":code) {
            case "progress_conflict"->new Error(code,"Позиция изменена в другой вкладке. Выберите, какую сохранить.");
            case "stale_position"->new Error(code,"Сохранённая позиция относится к другой версии книги.");
            case "stale_reader"->new Error(code,"Страница чтения устарела. Откройте книгу заново.");
            default->new Error(switch(status){case 400->"invalid_request";case 401->"authentication_required";case 403->"access_denied";case 404->"not_found";case 405->"method_not_allowed";case 406->"not_acceptable";case 408->"receive_timeout";case 413->"request_too_large";case 415->"unsupported_media_type";case 422->"invalid_position";case 429->"rate_limited";case 503->"temporarily_unavailable";default->"request_failed";},
                    switch(status){case 400,422->"Некорректная позиция или запрос.";case 401->"Войдите в аккаунт.";case 403->"Доступ запрещён. Проверьте сессию.";case 404->"Книга недоступна.";case 408->"Время приёма запроса истекло.";case 413->"Запрос слишком большой.";case 429->"Слишком много запросов. Повторите позже.";case 503->"Сохранение временно недоступно.";default->"Не удалось выполнить запрос.";});
        };
    }
    public static ResponseEntity<Error> response(int status,String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(error(status,code));
    }
    public static void write(HttpServletResponse response,int status)throws IOException {
        var body=error(status,null);response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"code\":\""+body.code()+"\",\"message\":\""+body.message()+"\"}");
    }
}
