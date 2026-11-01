package com.example.online_reader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class OnlineReaderApplicationTests {

    @Autowired
    private TestRestTemplate client;

    @Test
    void homePageRenders() {
        var response = client.getForEntity("/", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ДостоRead");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/js/passwordVisibilityChangerForLogin.bundle.js",
            "/js/passwordVisibilityChangerForRegistration.bundle.js"
    })
    void generatedPasswordBundleIsServed(String path) {
        var response = client.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getSubtype()).contains("javascript");
        assertThat(response.getBody()).isNotBlank();
    }

}
