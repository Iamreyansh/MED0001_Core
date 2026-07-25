package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.AdminMfaVerifyResult;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AdminVerifyMfaResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    boolean usedBackupCode,
    AdminDto admin) {

  public static AdminVerifyMfaResponse from(AdminMfaVerifyResult result) {
    return new AdminVerifyMfaResponse(
        result.accessToken(),
        result.refreshToken(),
        "Bearer",
        result.accessTtlSeconds(),
        result.refreshTtlSeconds(),
        result.usedBackupCode(),
        new AdminDto(
            result.admin().id(),
            result.admin().name(),
            result.admin().email(),
            result.admin().role(),
            result.admin().mfaEnabled(),
            result.backupCodesRemaining()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdminDto(
      UUID id,
      String name,
      String email,
      String role,
      boolean mfaEnabled,
      int backupCodesRemaining) {}
}
