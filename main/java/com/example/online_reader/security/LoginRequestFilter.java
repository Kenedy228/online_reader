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
import java.nio.charset.StandardCharsets;

/** Runs inside the security chain after CSRF, before the native username/password filter. */
public final class LoginRequestFilter extends OncePerRequestFilter {
    private final RequestMatcher login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");
    private final LoginAttemptLimiter limiter;

    public LoginRequestFilter(LoginAttemptLimiter limiter) { this.limiter = limiter; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !login.matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Count successes too; a known account cannot be used to reset a guessing budget.
        long retry = limiter.reserveAttempt(request.getRemoteAddr());
        if (retry > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retry));
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Слишком много попыток входа. Повторите позже.");
            return;
        }
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        boolean invalidUser = username == null || username.length() > 100 || username.trim().isEmpty()
                || username.trim().codePointCount(0, username.trim().length()) > 50;
        boolean invalidPassword = password == null || password.isEmpty()
                || password.length() > PasswordHashFormat.MAX_PASSWORD_BYTES
                || password.getBytes(StandardCharsets.UTF_8).length > PasswordHashFormat.MAX_PASSWORD_BYTES;
        if (invalidUser || invalidPassword) {
            response.sendRedirect(request.getContextPath() + "/login?error");
            return;
        }
        chain.doFilter(request, response);
    }
}
