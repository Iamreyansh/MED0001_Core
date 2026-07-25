package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LogoutAllResponse(int sessionsRevoked, String message) {

  public static LogoutAllResponse of(int sessionsRevoked) {
    return new LogoutAllResponse(sessionsRevoked, "All sessions have been terminated.");
  }
}
