package com.example.online_reader.user.repository;

import com.example.online_reader.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Integer> {
    User findUserByUsername(String name);
}
