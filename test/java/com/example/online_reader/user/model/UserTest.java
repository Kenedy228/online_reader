package com.example.online_reader.user.model;

import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import com.example.online_reader.config.PasswordConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@Import(PasswordConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class UserTest {

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savedUserCanBeFoundByUsername() {
        String username = "reader-" + UUID.randomUUID();
        userRepository.saveAndFlush(new User(username, encoder.encode("synthetic-test-only")));
        entityManager.clear();

        User loaded = userRepository.findUserByUsername(username);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo(username);
    }
}
