package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.SwitchPharmacyResult;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SwitchPharmacyResponse(
    String accessToken,
    String tokenType,
    long accessTokenExpiresIn,
    ActivePharmacyDto activePharmacy,
    String roleInPharmacy) {

  public static SwitchPharmacyResponse from(SwitchPharmacyResult result) {
    return new SwitchPharmacyResponse(
        result.accessToken(),
        "Bearer",
        result.accessTtlSeconds(),
        ActivePharmacyDto.from(result.pharmacy()),
        result.roleInPharmacy());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivePharmacyDto(
      UUID id, String name, String logoUrl, String city, String subscriptionPlan) {
    static ActivePharmacyDto from(PharmacyRecord r) {
      return new ActivePharmacyDto(r.id(), r.name(), r.logoUrl(), r.city(), r.subscriptionPlan());
    }
  }
}
