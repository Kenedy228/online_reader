package com.example.online_reader.book.progress;

import jakarta.validation.constraints.*;

/** Public locator only, never an account ID or storage path. */
public record ReadingPosition(
        @NotNull @Pattern(regexp="text|s[0-9]{1,4}") String chapter,
        @NotNull @Min(0) @Max(10485760) Integer offset,
        @NotNull @Pattern(regexp="[0-9a-f]{64}") String fingerprint,
        @NotNull @Min(1) @Max(1) Integer version) { }
