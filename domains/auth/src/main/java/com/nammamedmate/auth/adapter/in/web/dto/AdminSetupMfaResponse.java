package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.AdminSetupMfaResult;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AdminSetupMfaResponse(String totpUri, String totpSecret, List<String> backupCodes) {

  public AdminSetupMfaResponse {
    backupCodes = List.copyOf(backupCodes);
  }

  public static AdminSetupMfaResponse from(AdminSetupMfaResult result) {
    return new AdminSetupMfaResponse(result.totpUri(), result.totpSecret(), result.backupCodes());
  }
}
