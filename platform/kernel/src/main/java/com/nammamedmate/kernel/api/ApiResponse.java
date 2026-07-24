package com.nammamedmate.kernel.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, PaginationMeta meta, ApiError error) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null, null);
  }

  public static <T> ApiResponse<T> ok(T data, PaginationMeta meta) {
    return new ApiResponse<>(true, data, meta, null);
  }

  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(false, null, null, new ApiError(code, message));
  }
}
