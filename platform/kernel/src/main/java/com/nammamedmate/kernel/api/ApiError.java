package com.nammamedmate.kernel.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String code,
    String message,
    @JsonProperty("retry_after_seconds") Integer retryAfterSeconds,
    Map<String, Object> details) {

  public ApiError {
    details = details == null ? null : Map.copyOf(details);
  }

  public ApiError(String code, String message) {
    this(code, message, null, null);
  }

  public ApiError(String code, String message, Integer retryAfterSeconds) {
    this(code, message, retryAfterSeconds, null);
  }
}
