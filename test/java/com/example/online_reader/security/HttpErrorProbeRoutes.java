package com.example.online_reader.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

/** Raise real framework errors behind the production admin policy, with deliberately sensitive-looking details. */
@TestConfiguration(proxyBeanMethods = false)
public class HttpErrorProbeRoutes {
    static final String SENSITIVE = "synthetic-SQL-select-password-stacktrace-probe";

    @Bean
    RouterFunction<ServerResponse> httpErrorProbes() {
        return route(GET("/admin/http-test/not-found"), request -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SENSITIVE);
        }).andRoute(GET("/admin/http-test/bad-request"), request -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SENSITIVE);
        }).andRoute(GET("/admin/http-test/server-error"), request -> {
            throw new IllegalStateException(SENSITIVE);
        });
    }
}
