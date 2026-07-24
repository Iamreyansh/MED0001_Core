package com.nammamedmate.kernel.api;

public record PaginationMeta(int page, int limit, long total, boolean hasNext) {

  public static PaginationMeta of(int page, int limit, long total) {
    boolean hasNext = (long) page * limit < total;
    return new PaginationMeta(page, limit, total, hasNext);
  }
}
