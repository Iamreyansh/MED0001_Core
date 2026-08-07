package com.nammamedmate.pharmacy.adapter.out.metrics;

import com.nammamedmate.pharmacy.application.port.out.CatalogueVisibilityPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ponytail: in-memory visibility until EPIC-005 catalogue mapping is wired. */
public final class StubCatalogueVisibilityClient implements CatalogueVisibilityPort {

  private final PharmacyCatalogueStatsPort catalogueStats;
  private final Map<UUID, Boolean> hidden = new ConcurrentHashMap<>();

  public StubCatalogueVisibilityClient(PharmacyCatalogueStatsPort catalogueStats) {
    this.catalogueStats = catalogueStats;
  }

  @Override
  public int hideAll(UUID pharmacyId) {
    int count = catalogueStats.catalogueStats(pharmacyId).mappedSkus();
    if (count <= 0) {
      count = 100;
    }
    hidden.put(pharmacyId, true);
    return count;
  }

  @Override
  public void restoreAll(UUID pharmacyId) {
    hidden.remove(pharmacyId);
  }

  public boolean isHidden(UUID pharmacyId) {
    return hidden.containsKey(pharmacyId);
  }
}
