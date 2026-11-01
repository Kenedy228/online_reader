package com.example.online_reader.security;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class HttpSessionLifecycleTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext webContext;

    @Test
    void realContainerRotatesSessionAndLogoutKillsReplay() throws Exception {
        String username = "http-reader";
        users.saveAndFlush(new User(username, encoder.encode("synthetic-http-password")));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        var page = send(client, "GET", "/login", null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        String header = page.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(header).contains("JSESSIONID=", "HttpOnly", "SameSite=Lax").doesNotContain("Secure", "Max-Age");
        String before = sessionId(cookies);
        assertThat(webContext.getServletContext().getSessionTimeout()).isEqualTo(15);
        var login = send(client, "POST", "/login", "_csrf=" + csrf(page.body()) + "&username=" + username
                + "&password=synthetic-http-password", null);
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElseThrow()).endsWith("/");
        String after = sessionId(cookies);
        assertThat(after).isNotEqualTo(before);
        assertThat(send(client, "GET", "/login", null, null).statusCode()).isEqualTo(302);
        assertThat(send(client, "POST", "/logout", "", null).statusCode()).isEqualTo(403);
        var authenticated = send(client, "GET", "/", null, null);
        assertThat(authenticated.body()).contains(username, "action=\"/logout\"").doesNotContain(";jsessionid");
        var logout = send(client, "POST", "/logout", "_csrf=" + csrf(authenticated.body()), null);
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().allValues("Set-Cookie")).anyMatch(value -> value.contains("Max-Age=0"));
        var replay = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        assertThat(send(replay, "GET", "/login", null, "JSESSIONID=" + after).statusCode()).isEqualTo(200);
        assertThat(send(replay, "GET", "/login", null, "JSESSIONID=" + before).statusCode()).isEqualTo(200);
        assertThat(send(client, "GET", "/login", null, null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String form, String cookie)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) request.header("Cookie", cookie);
        if (method.equals("POST")) request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        else request.GET();
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionId(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().getValue();
    }

    private static String csrf(String html) {
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        return URLEncoder.encode(token.group(1), StandardCharsets.UTF_8);
    }
}
