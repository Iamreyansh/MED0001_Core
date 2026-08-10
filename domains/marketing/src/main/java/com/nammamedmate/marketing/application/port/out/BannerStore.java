package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.Banner;
import com.nammamedmate.marketing.domain.BannerPlacement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BannerStore {

  record ReorderItem(UUID id, int priority) {}

  Banner insert(Banner banner);

  Optional<Banner> findById(UUID id);

  List<Banner> list(BannerPlacement placement, Boolean live, int offset, int limit);

  long count(BannerPlacement placement, Boolean live);

  List<Banner> listActiveForPlacement(BannerPlacement placement, Instant now);

  void update(Banner banner);

  void hardDelete(UUID id);

  /** Atomically set priorities for items that must already share one placement. */
  int reorder(List<ReorderItem> items, Instant updatedAt);

  List<Banner> findByIds(List<UUID> ids);

  int deactivateExpired(Instant now);

  boolean incrementImpressions(UUID id);

  boolean incrementClicks(UUID id);
}
