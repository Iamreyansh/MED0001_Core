package com.nammamedmate.pharmacy.adapter.out.metrics;

import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import java.util.UUID;

/** ponytail: zero catalogue counts until catalogue mapping domain is wired. */
public final class StubPharmacyCatalogueStatsClient implements PharmacyCatalogueStatsPort {

  @Override
  public CatalogueStats catalogueStats(UUID pharmacyId) {
    return new CatalogueStats(0, 0, 0);
  }
}
