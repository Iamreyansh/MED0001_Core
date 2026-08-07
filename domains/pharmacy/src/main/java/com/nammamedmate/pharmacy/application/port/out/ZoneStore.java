package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ZoneStore {

  record ZoneRecord(UUID id, String name, boolean active) {}

  record AdminZoneRow(
      UUID zoneId,
      String zoneName,
      String city,
      String state,
      boolean active,
      int pharmacyCount,
      int onlinePharmacyCount,
      BigDecimal coverageAreaSqkm,
      Instant createdAt) {}

  Optional<ZoneRecord> findById(UUID id);

  List<AdminZoneRow> listForAdmin(String city, Boolean isActive);
}
