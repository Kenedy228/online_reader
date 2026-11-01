package com.example.online_reader.security;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@Import(HttpErrorProbeRoutes.class)
class HttpErrorPolicyTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    static final String PASSWORD = "synthetic-http-policy-password";

    @Test
    void negotiationAndApplicationErrorsAreSanitizedThroughRealErrorDispatch() throws Exception {
        var anonymous = client();
        var redirect = send(anonymous, "GET", "/unregistered?password=do-not-reflect-query", "text/html", null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(URI.create(redirect.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/login");
        headers(redirect);
        for (String accept : new String[]{"application/json", "application/problem+json", "application/vnd.reader+json"})
            problem(send(anonymous, "GET", "/unregistered?password=do-not-reflect-query", accept, null), 401);
        var ordinary = login(false);
        problem(send(ordinary, "GET", "/admin/http-test/not-found", "application/json", null), 403);
        var admin = login(true);
        for (var scenario : Map.of("not-found", 404, "bad-request", 400, "server-error", 500).entrySet())
            problem(send(admin, "GET", "/admin/http-test/" + scenario.getKey() + "?password=do-not-reflect-query",
                    "application/json", null), scenario.getValue());
        // No probe handles this URI: exercise the real missing-resource path after authorization.
        problem(send(admin, "GET", "/admin/no-such-page?password=do-not-reflect-query", "application/json", null), 404);
        var html = send(admin, "GET", "/admin/http-test/not-found?password=do-not-reflect-query", "text/html", null);
        assertThat(html.statusCode()).isEqualTo(404);
        assertThat(html.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(html.body()).contains("lang=\"ru\"", "<main", "Страница не найдена")
                .doesNotContain(HttpErrorProbeRoutes.SENSITIVE, "do-not-reflect-query", "java.lang", PASSWORD, "Whitelabel");
        headers(html);
    }

    @Test
    void csrfRejectsMissingInvalidAndCrossSessionTokensWithoutMutatingAccountsOrLogout() throws Exception {
        var browser = client();
        var page = send(browser, "GET", "/registration", "text/html", null);
        String token = csrf(page.body());
        long before = users.count();
        String name = "http-" + UUID.randomUUID();
        String fields = "username=" + name + "&email=" + name + "@example.test&password=" + PASSWORD
                + "&confirmPassword=" + PASSWORD;
        problem(send(browser, "POST", "/registration", "application/json", fields), 403);
        problem(send(browser, "POST", "/registration", "application/json", fields + "&_csrf=invalid-probe"), 403);
        var other = client();
        String otherToken = csrf(send(other, "GET", "/login", "text/html", null).body());
        problem(send(browser, "POST", "/registration", "application/json", fields + "&_csrf=" + otherToken), 403);
        assertThat(users.count()).isEqualTo(before);
        send(browser, "GET", "/registration?username=" + name + "&password=" + PASSWORD, "text/html", null);
        assertThat(users.count()).isEqualTo(before);
        var success = send(browser, "POST", "/registration", "text/html", fields + "&_csrf=" + token);
        assertThat(success.statusCode()).isEqualTo(302); headers(success);
        assertThat(encoder.matches(PASSWORD, users.findUserByUsername(name).getPasswordHash())).isTrue();
        token = csrf(send(browser, "GET", "/login", "text/html", null).body());
        problem(send(browser, "POST", "/login", "application/json", fields + "&_csrf=invalid-probe"), 403);
        assertThat(send(browser, "POST", "/login", "text/html", fields + "&_csrf=" + token).statusCode()).isEqualTo(302);
        send(browser, "GET", "/logout", "text/html", null);
        assertThat(send(browser, "GET", "/login", "text/html", null).statusCode()).isEqualTo(302);
        problem(send(browser, "POST", "/logout", "application/json", "_csrf=invalid-probe"), 403);
        problem(send(browser, "POST", "/logout", "application/json", ""), 403);
        assertThat(send(browser, "GET", "/login", "text/html", null).statusCode()).isEqualTo(302);
        token = csrf(send(browser, "GET", "/", "text/html", null).body());
        var logout = send(browser, "POST", "/logout", "text/html", "_csrf=" + token);
        assertThat(logout.statusCode()).isEqualTo(302); headers(logout);
        assertThat(send(browser, "GET", "/login", "text/html", null).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidRegistrationKeepsHtmlValidationContractAndNoSensitiveValues() throws Exception {
        var browser = client();
        String token = csrf(send(browser, "GET", "/registration", "text/html", null).body());
        long before = users.count();
        var response = send(browser, "POST", "/registration", "text/html", "_csrf=" + token
                + "&username=valid-name&email=invalid&password=" + PASSWORD + "&confirmPassword=different-password-probe");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(response.body()).contains("aria-invalid=\"true\"").doesNotContain(PASSWORD, "different-password-probe");
        assertThat(users.count()).isEqualTo(before);
        headers(response);
    }

    @Test
    void firewallRejectionStillHasSafeErrorAndSecurityHeaders() throws Exception {
        problem(send(client(), "GET", "/admin//http-test/not-found", "application/json", null), 400);
    }

    private HttpClient login(boolean admin) throws Exception {
        String name = "http-" + UUID.randomUUID();
        var user = users.saveAndFlush(new User(name, encoder.encode(PASSWORD)));
        if (admin) jdbc.update("update users set role='Admin' where id=?", user.getId());
        var client = client();
        String token = csrf(send(client, "GET", "/login", "text/html", null).body());
        assertThat(send(client, "POST", "/login", "text/html", "username=" + name + "&password=" + PASSWORD
                + "&_csrf=" + token).statusCode()).isEqualTo(302);
        return client;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String accept, String form) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", accept);
        if (method.equals("POST")) request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        else request.GET();
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(expected);
        assertThat(body.path("instance").asText()).isEqualTo("/error");
        assertThat(body.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
        assertThat(response.body()).doesNotContain(HttpErrorProbeRoutes.SENSITIVE, "do-not-reflect-query", PASSWORD,
                "invalid-probe", "java.lang", "org.springframework", "Whitelabel");
        headers(response);
    }

    private static void headers(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("script-src 'self'", "frame-ancestors 'none'").doesNotContain("unsafe-inline", "unsafe-eval");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    private static String csrf(String html) {
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        return URLEncoder.encode(token.group(1), StandardCharsets.UTF_8);
    }
}
