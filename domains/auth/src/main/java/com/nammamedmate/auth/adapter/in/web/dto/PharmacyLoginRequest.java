package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PharmacyLoginRequest(
    @NotBlank String identifier, @NotBlank @Size(min = 8) String password, UUID pharmacyId) {}
