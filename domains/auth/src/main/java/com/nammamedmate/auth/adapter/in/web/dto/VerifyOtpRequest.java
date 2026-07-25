package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VerifyOtpRequest(
    @NotNull UUID sessionId,
    @NotBlank @Pattern(regexp = "^\\+91[6-9]\\d{9}$") String phone,
    @NotBlank @Pattern(regexp = "^\\d{6}$") String otp,
    @Size(max = 512) String deviceToken) {}
