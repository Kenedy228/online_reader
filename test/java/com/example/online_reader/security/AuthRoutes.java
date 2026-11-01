package com.example.online_reader.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.security.Principal;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

@TestConfiguration(proxyBeanMethods = false)
public class AuthRoutes {
    @Bean
    RouterFunction<ServerResponse> protectedTestRoute() {
        return route(GET("/auth-test/protected"), request -> ServerResponse.ok()
                .body(request.principal().map(Principal::getName).orElse("anonymous")))
                .andRoute(GET("/auth-test/timeout"), request -> ServerResponse.ok()
                        .body(Integer.toString(request.servletRequest().getSession().getMaxInactiveInterval())));
    }
}
