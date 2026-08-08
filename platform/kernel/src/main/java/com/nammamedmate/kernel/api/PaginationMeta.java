package com.nammamedmate.kernel.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaginationMeta(
    int page, int limit, long total, boolean hasNext, Long unassignedTotal) {

  public static PaginationMeta of(int page, int limit, long total) {
    boolean hasNext = (long) page * limit < total;
    return new PaginationMeta(page, limit, total, hasNext, null);
  }

  public static PaginationMeta of(int page, int limit, long total, long unassignedTotal) {
    boolean hasNext = (long) page * limit < total;
    return new PaginationMeta(page, limit, total, hasNext, unassignedTotal);
  }
}
