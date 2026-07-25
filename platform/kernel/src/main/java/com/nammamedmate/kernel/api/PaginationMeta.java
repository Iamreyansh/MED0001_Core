package com.nammamedmate.kernel.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PaginationMeta(int page, int limit, long total, boolean hasNext) {

  public static PaginationMeta of(int page, int limit, long total) {
    boolean hasNext = (long) page * limit < total;
    return new PaginationMeta(page, limit, total, hasNext);
  }
}
