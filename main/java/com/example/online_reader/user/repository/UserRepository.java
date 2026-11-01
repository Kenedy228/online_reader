package com.example.online_reader.user.repository;

import com.example.online_reader.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Integer> {
    User findUserByUsername(String name);
    // DB uniqueness is the authority under concurrency; a conflict never updates another account.
    // Native ON CONFLICT avoids exception logs containing duplicate email/username values.
    @Modifying
    @Query(value = """
            INSERT INTO users(username, email, password, account_non_expired, account_non_locked,
                              credentials_non_expired, enabled)
            VALUES (:username, :email, :passwordHash, true, true, true, true)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertRegisteredUser(@Param("username") String username, @Param("email") String email,
                             @Param("passwordHash") String passwordHash);
}
