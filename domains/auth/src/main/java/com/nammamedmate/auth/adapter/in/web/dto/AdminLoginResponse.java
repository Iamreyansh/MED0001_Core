package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.AdminLoginResult;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminLoginResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    Long accessTokenExpiresIn,
    Long refreshTokenExpiresIn,
    Boolean mfaRequired,
    String mfaChallengeToken,
    Long mfaChallengeExpiresIn,
    UUID adminId,
    AdminDto admin) {

  public static AdminLoginResponse from(AdminLoginResult result) {
    if (result.mfaRequired()) {
      return new AdminLoginResponse(
          null,
          null,
          null,
          null,
          null,
          true,
          result.mfaChallengeToken(),
          result.mfaChallengeExpiresIn(),
          result.adminId(),
          null);
    }
    return new AdminLoginResponse(
        result.accessToken(),
        result.refreshToken(),
        "Bearer",
        result.accessTtlSeconds(),
        result.refreshTtlSeconds(),
        false,
        null,
        null,
        null,
        AdminDto.from(result.admin(), null));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AdminDto(
      UUID id,
      String name,
      String email,
      String role,
      boolean mfaEnabled,
      Integer backupCodesRemaining) {
    static AdminDto from(AdminStaffRecord a, Integer remaining) {
      return new AdminDto(a.id(), a.name(), a.email(), a.role(), a.mfaEnabled(), remaining);
    }
  }
}
