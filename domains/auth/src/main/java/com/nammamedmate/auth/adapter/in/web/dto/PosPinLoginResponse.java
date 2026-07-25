package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.PosPinLoginResult;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PosPinLoginResponse(
    String accessToken,
    String tokenType,
    String tokenScope,
    long accessTokenExpiresIn,
    PosStaffDto staff,
    PosPharmacyDto pharmacy) {

  public static PosPinLoginResponse from(PosPinLoginResult result) {
    return new PosPinLoginResponse(
        result.accessToken(),
        "Bearer",
        "pos",
        result.accessTtlSeconds(),
        PosStaffDto.from(result.staff(), result.roleInPharmacy()),
        PosPharmacyDto.from(result.pharmacy()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PosStaffDto(UUID id, String name, String role) {
    static PosStaffDto from(PharmacyStaffRecord s, String role) {
      return new PosStaffDto(s.id(), s.name(), role);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PosPharmacyDto(UUID id, String name) {
    static PosPharmacyDto from(PharmacyRecord p) {
      return new PosPharmacyDto(p.id(), p.name());
    }
  }
}
