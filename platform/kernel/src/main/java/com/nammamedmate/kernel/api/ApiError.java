package com.nammamedmate.kernel.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String code, String message, @JsonProperty("retry_after_seconds") Integer retryAfterSeconds) {

  public ApiError(String code, String message) {
    this(code, message, null);
  }
}
