package com.nammamedmate.kernel.error;

public class AppException extends RuntimeException {

  private final String code;
  private final int httpStatus;

  public AppException(String code, String message, int httpStatus) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  public String code() {
    return code;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
