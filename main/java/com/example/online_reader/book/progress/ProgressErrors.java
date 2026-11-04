package com.example.online_reader.book.progress;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice(assignableTypes=ProgressController.class)
public class ProgressErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProgressHttp.Error> failure(Exception error) {
        if(error instanceof ProgressService.Failure failure)return ProgressHttp.response(failure.status(),failure.code());
        int status=error instanceof org.springframework.security.access.AccessDeniedException?403:
                error instanceof org.springframework.web.server.ResponseStatusException failure?failure.getStatusCode().value():
                error instanceof org.springframework.dao.DataAccessException || error instanceof com.example.online_reader.book.files.BookFileException?503:
                error instanceof org.springframework.web.HttpMediaTypeNotSupportedException?415:
                error instanceof org.springframework.web.HttpMediaTypeNotAcceptableException?406:
                error instanceof org.springframework.web.HttpRequestMethodNotSupportedException?405:
                error instanceof org.springframework.web.bind.ServletRequestBindingException || error instanceof org.springframework.beans.TypeMismatchException || error instanceof org.springframework.http.converter.HttpMessageNotReadableException?400:500;
        return ProgressHttp.response(status,null);
    }
}
