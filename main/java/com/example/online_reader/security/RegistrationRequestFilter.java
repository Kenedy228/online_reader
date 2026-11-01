package com.example.online_reader.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Separate registration budget, after native CSRF and before validation/BCrypt. */
public final class RegistrationRequestFilter extends OncePerRequestFilter {
    private final RequestMatcher registration = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/registration");
    private final LoginAttemptLimiter limiter;

    public RegistrationRequestFilter(LoginAttemptLimiter limiter) { this.limiter = limiter; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !registration.matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retry = limiter.reserveAttempt(request.getRemoteAddr());
        if (retry > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retry));
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Слишком много попыток регистрации. Повторите позже.");
            return;
        }
        chain.doFilter(request, response);
    }
}
