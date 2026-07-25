package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.PharmacyLoginResult;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PharmacyLoginResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    PharmacyDto activePharmacy,
    StaffDto staff,
    List<PharmacyItemDto> pharmacies) {

  public PharmacyLoginResponse {
    pharmacies = List.copyOf(pharmacies);
  }

  public static PharmacyLoginResponse from(PharmacyLoginResult result) {
    return new PharmacyLoginResponse(
        result.accessToken(),
        result.refreshToken(),
        "Bearer",
        result.accessTtlSeconds(),
        result.refreshTtlSeconds(),
        PharmacyDto.from(result.activePharmacy()),
        StaffDto.from(result.staff(), result.roleInActivePharmacy()),
        result.assignments().stream().map(PharmacyItemDto::from).toList());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PharmacyDto(
      UUID id, String name, String logoUrl, String city, String subscriptionPlan) {
    static PharmacyDto from(PharmacyRecord r) {
      return new PharmacyDto(r.id(), r.name(), r.logoUrl(), r.city(), r.subscriptionPlan());
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StaffDto(
      UUID id, String name, String email, String phone, String role, boolean mfaEnabled) {
    static StaffDto from(PharmacyStaffRecord s, String role) {
      return new StaffDto(s.id(), s.name(), s.email(), s.phone(), role, false);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PharmacyItemDto(UUID id, String name, String role, boolean isActive) {
    static PharmacyItemDto from(PharmacyAssignmentRecord a) {
      return new PharmacyItemDto(a.pharmacyId(), a.pharmacyName(), a.roleCode(), a.isActive());
    }
  }
}
