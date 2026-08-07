package com.nammamedmate.api.config;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.CatalogueStats;
import com.nammamedmate.pharmacy.application.port.out.CatalogueVisibilityPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition-root bridge: pharmacy ports → catalogue mapping JDBC (no domain→domain deps). */
@Configuration
public class CataloguePharmacyBridgeConfig {

  @Bean
  CatalogueVisibilityPort catalogueVisibilityPort(MedicineMappingStore store) {
    return new CatalogueVisibilityPort() {
      @Override
      public int hideAll(UUID pharmacyId) {
        return store.hideAllForPharmacy(pharmacyId);
      }

      @Override
      public void restoreAll(UUID pharmacyId) {
        store.restoreAllForPharmacy(pharmacyId);
      }
    };
  }

  @Bean
  PharmacyCatalogueStatsPort pharmacyCatalogueStatsPort(MedicineMappingStore store) {
    return pharmacyId -> {
      CatalogueStats stats = store.statsForPharmacy(pharmacyId);
      return new PharmacyCatalogueStatsPort.CatalogueStats(
          stats.mappedSkus(), stats.inStockSkus(), stats.outOfStockSkus());
    };
  }
}
