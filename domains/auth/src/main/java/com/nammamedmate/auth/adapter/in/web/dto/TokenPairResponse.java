package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.TokenPairResult;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TokenPairResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn) {

  public static TokenPairResponse from(TokenPairResult result) {
    return new TokenPairResponse(
        result.accessToken(),
        result.refreshToken(),
        result.tokenType(),
        result.accessTokenExpiresIn(),
        result.refreshTokenExpiresIn());
  }
}
