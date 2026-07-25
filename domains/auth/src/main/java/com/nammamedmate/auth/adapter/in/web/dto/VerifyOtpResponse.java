package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.VerifyOtpResult;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VerifyOtpResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    boolean isNewUser,
    CustomerResponse customer) {

  public static VerifyOtpResponse from(VerifyOtpResult result) {
    return new VerifyOtpResponse(
        result.accessToken(),
        result.refreshToken(),
        result.tokenType(),
        result.accessTokenExpiresIn(),
        result.refreshTokenExpiresIn(),
        result.newUser(),
        CustomerResponse.from(result.customer()));
  }
}
