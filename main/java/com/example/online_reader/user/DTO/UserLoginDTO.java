package com.example.online_reader.user.DTO;

import jakarta.validation.constraints.NotBlank;

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

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }
}
