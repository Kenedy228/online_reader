package com.example.online_reader.config;

import com.example.online_reader.security.LoginAttemptLimiter;
import com.example.online_reader.security.AdminAccessPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import com.example.online_reader.security.LoginRequestFilter;
import com.example.online_reader.security.RegistrationRequestFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    // Only application-owned resources are public; never expose a whole static directory.
    private static final String[] PUBLIC_RESOURCES = {
            "/assets/goal.png", "/assets/hide.png", "/assets/logo.svg", "/assets/show.png",
            "/styles/footer.css", "/styles/header.css", "/styles/index.css", "/styles/login.css",
            "/styles/registration.css", "/styles/styles.css",
            "/js/passwordVisibilityChangerForLogin.bundle.js",
            "/js/passwordVisibilityChangerForRegistration.bundle.js"
    };
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        // Compare the password before all status checks; locked/disabled accounts must not
        // create a fast credential-enumeration path. Unknown-user timing uses the native dummy hash.
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return new ProviderManager(provider);
    }

    @Bean
    LoginAttemptLimiter loginAttemptLimiter(@Value("${online-reader.auth.max-attempts}") int attempts,
                                           @Value("${online-reader.auth.max-clients}") int clients,
                                           @Value("${online-reader.auth.window}") String window) {
        return new LoginAttemptLimiter(attempts, clients, DurationStyle.detectAndParse(window));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authentication,
                                            LoginAttemptLimiter limiter, AdminAccessPolicy adminAccess,
                                            @Value("${online-reader.registration.max-attempts}") int registrationAttempts,
                                            @Value("${online-reader.registration.max-clients}") int registrationClients,
                                            @Value("${online-reader.registration.window}") String registrationWindow) throws Exception {
        http.authenticationManager(authentication)
                .authorizeHttpRequests(rules -> rules.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/login", "/registration").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/", "/login", "/registration").permitAll()
                        .requestMatchers(HttpMethod.POST, "/login", "/registration").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_RESOURCES).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_RESOURCES).permitAll()
                        .requestMatchers("/admin/**").access((auth, context) ->
                                new AuthorizationDecision(adminAccess.isAllowed(auth.get())))
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        // Do not put the exception, submitted username or credentials in the session.
                        .failureHandler((request, response, error) ->
                                response.sendRedirect(request.getContextPath() + "/login?error")))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));
        // Construct here, not as a Filter bean: Boot must not register it a second time outside CSRF.
        http.addFilterBefore(new LoginRequestFilter(limiter), UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(new RegistrationRequestFilter(new LoginAttemptLimiter(registrationAttempts,
                registrationClients, DurationStyle.detectAndParse(registrationWindow))),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
