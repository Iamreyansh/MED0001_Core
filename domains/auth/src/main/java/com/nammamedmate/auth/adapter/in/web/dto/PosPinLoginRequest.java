package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PosPinLoginRequest(
    @NotNull UUID pharmacyId,
    @NotNull UUID staffId,
    @NotBlank @Pattern(regexp = "\\d{4}") String pin) {}
