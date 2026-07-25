package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SendOtpRequest(
    @NotBlank @Pattern(regexp = "^\\+91[6-9]\\d{9}$") String phone,
    @Valid DeviceInfoRequest deviceInfo) {}
