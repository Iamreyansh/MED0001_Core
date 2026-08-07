package com.nammamedmate.catalogue.application.port.out;

import java.util.Optional;

public interface CategoryListCachePort {

  String CACHE_KEY = "catalogue:categories:public";

  Optional<String> get();

  void put(String json);

  void invalidate();
}
