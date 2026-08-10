package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.HelpArticle;
import com.nammamedmate.support.domain.TicketCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HelpArticleStore {

  record ListFilter(
      TicketCategory category,
      Boolean published,
      String q,
      boolean publicOnly,
      int offset,
      int limit) {}

  record CategoryCount(String name, long articleCount) {}

  HelpArticle insert(HelpArticle row);

  HelpArticle update(HelpArticle row);

  Optional<HelpArticle> findById(UUID id);

  List<HelpArticle> list(ListFilter filter);

  long count(ListFilter filter);

  List<CategoryCount> publishedCategoryCounts(String q);

  int incrementViewCount(UUID id);

  int incrementDeflectionCount(UUID id);
}
