package com.example.online_reader.user.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class UserLoginDTO {
    @NotBlank(message = "Поле имя пользователя обязательно для заполнения")
    private String username;
    @NotBlank(message = "Поле пароль обязательно для заполнения")
    private String password;

    public UserLoginDTO() {

    }

    public UserLoginDTO(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
