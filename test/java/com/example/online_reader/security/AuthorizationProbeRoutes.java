package com.example.online_reader.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

/** Deliberately unguarded handlers: the production security chain must decide access first. */
@TestConfiguration(proxyBeanMethods = false)
public class AuthorizationProbeRoutes {
    @Bean
    RouterFunction<ServerResponse> authorizationProbe() {
        return route(GET("/unregistered/security-test"), request -> ServerResponse.noContent().build())
                .andRoute(GET("/admin/security-test"), request -> ServerResponse.noContent().build());
    }
}
