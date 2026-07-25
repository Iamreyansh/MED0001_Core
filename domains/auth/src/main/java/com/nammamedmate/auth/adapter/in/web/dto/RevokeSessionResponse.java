package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RevokeSessionResponse(UUID sessionId, String message) {

  public static RevokeSessionResponse of(UUID sessionId) {
    return new RevokeSessionResponse(sessionId, "Session revoked.");
  }
}
