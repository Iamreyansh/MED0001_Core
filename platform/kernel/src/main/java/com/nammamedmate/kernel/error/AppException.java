package com.nammamedmate.kernel.error;

import java.util.Map;

public class AppException extends RuntimeException {

  private final String code;
  private final int httpStatus;
  private final Integer retryAfterSeconds;
  private final Map<String, Object> details;

  public AppException(String code, String message, int httpStatus) {
    this(code, message, httpStatus, null, null);
  }

  public AppException(String code, String message, int httpStatus, Integer retryAfterSeconds) {
    this(code, message, httpStatus, retryAfterSeconds, null);
  }

  public AppException(
      String code,
      String message,
      int httpStatus,
      Integer retryAfterSeconds,
      Map<String, Object> details) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.retryAfterSeconds = retryAfterSeconds;
    this.details = details == null ? null : Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public Map<String, Object> details() {
    return details;
  }
}
