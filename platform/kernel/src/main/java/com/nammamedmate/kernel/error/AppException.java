package com.nammamedmate.kernel.error;

public class AppException extends RuntimeException {

  private final String code;
  private final int httpStatus;
  private final Integer retryAfterSeconds;

  public AppException(String code, String message, int httpStatus) {
    this(code, message, httpStatus, null);
  }

  public AppException(String code, String message, int httpStatus, Integer retryAfterSeconds) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.retryAfterSeconds = retryAfterSeconds;
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
}
