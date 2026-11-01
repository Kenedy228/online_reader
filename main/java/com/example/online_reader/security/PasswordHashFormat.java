package com.example.online_reader.security;

import java.util.regex.Pattern;

/** Stored format accepted by this application; no plaintext or fallback encoders. */
public final class PasswordHashFormat {
    public static final int WORK_FACTOR = 12;
    public static final int MAX_PASSWORD_BYTES = 72;
    public static final String BCRYPT = "^\\{bcrypt\\}\\$2[aby]\\$" + WORK_FACTOR + "\\$[./A-Za-z0-9]{53}$";
    private static final Pattern SUPPORTED = Pattern.compile(BCRYPT);

    private PasswordHashFormat() { }

    public static boolean supports(String value) {
        return value != null && SUPPORTED.matcher(value).matches();
    }
}
