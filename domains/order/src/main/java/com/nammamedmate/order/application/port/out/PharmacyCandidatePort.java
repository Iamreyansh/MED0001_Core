package com.nammamedmate.order.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Order-owned JDBC reads against pharmacies + directory/performance metrics. */
public interface PharmacyCandidatePort {

  record PharmacyRow(
      UUID id,
      String name,
      String area,
      String addressLine,
      String logoUrl,
      String currentOffer,
      Double latitude,
      Double longitude,
      boolean online,
      boolean adminForcedOffline,
      String status,
      double rating,
      int reviewCount,
      double fillRatePct,
      Double avgPrepMinutes) {

    /** Open for customer discovery: online, not banned (suspended), not admin-forced offline. */
    public boolean isOpen() {
      return online
          && !adminForcedOffline
          && !"SUSPENDED".equals(status)
          && "ACTIVE".equals(status);
    }
  }

  List<PharmacyRow> findOpenNear(double lat, double lng, double radiusKm);

  Optional<PharmacyRow> findById(UUID pharmacyId);

  List<String> categoriesAvailable(UUID pharmacyId);

  int visibleItemsCount(UUID pharmacyId);

  Optional<String> openHoursSummary(UUID pharmacyId);

  /** ponytail: fill rates already live on pharmacy_directory_metrics; no copy needed. */
  int refreshFillRatesFromDirectoryMetrics();
}
