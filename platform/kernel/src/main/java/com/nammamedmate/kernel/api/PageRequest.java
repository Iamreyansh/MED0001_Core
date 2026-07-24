package com.nammamedmate.kernel.api;

public record PageRequest(int page, int limit, String sort, String order) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public static PageRequest normalize(Integer page, Integer limit, String sort, String order) {
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
    String o = "desc".equalsIgnoreCase(order) ? "desc" : "asc";
    return new PageRequest(p, l, sort, o);
  }

  public int offset() {
    return (page - 1) * limit;
  }
}
