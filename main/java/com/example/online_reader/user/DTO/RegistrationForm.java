package com.example.online_reader.user.DTO;

import com.example.online_reader.security.PasswordHashFormat;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Public registration input only; never bind a JPA account or account flags. */
public class RegistrationForm {
    public static final String USERNAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9_.\\-]{2,49}";
    @NotBlank(message = "Введите имя пользователя.")
    @Pattern(regexp = USERNAME_PATTERN, message = "Используйте 3–50 латинских букв, цифр и _.-; первый символ — буква или цифра.")
    private String username;

    @NotBlank(message = "Введите email.")
    @Email(message = "Введите корректный email.")
    @Size(max = 254, message = "Email должен быть не длиннее 254 символов.")
    @Pattern(regexp = "[\\x21-\\x7E]+", message = "Используйте email из латинских символов без пробелов.")
    private String email;

    @NotBlank(message = "Введите пароль.")
    @CodePointLength(min = 15, max = PasswordHashFormat.MAX_PASSWORD_BYTES,
            message = "Пароль должен содержать не менее 15 символов и не более 72 байт UTF-8.")
    private String password;

    @NotBlank(message = "Подтвердите пароль.")
    @Size(max = PasswordHashFormat.MAX_PASSWORD_BYTES, message = "Подтверждение пароля слишком длинное.")
    private String confirmPassword;

    @AssertTrue(message = "Пароль должен занимать не более 72 байт UTF-8.")
    public boolean isPasswordWithinByteLimit() {
        return password == null || (password.length() <= PasswordHashFormat.MAX_PASSWORD_BYTES
                && password.getBytes(StandardCharsets.UTF_8).length <= PasswordHashFormat.MAX_PASSWORD_BYTES);
    }

    @AssertTrue(message = "Пароли должны совпадать.")
    public boolean isPasswordsMatch() { return Objects.equals(password, confirmPassword); }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? null : username.trim(); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT); }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
    public void clearPasswords() { password = null; confirmPassword = null; }
}
