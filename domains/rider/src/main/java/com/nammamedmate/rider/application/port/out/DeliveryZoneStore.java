package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryZoneStore {

  record ZoneRow(
      UUID id,
      String name,
      String city,
      String state,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      int slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      boolean surgeActive,
      boolean serviceable,
      String offlineReason,
      boolean active,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  record ZoneSummaryRow(
      UUID id,
      String name,
      String city,
      BigDecimal baseFee,
      int slaMinutes,
      BigDecimal surgeMultiplier,
      boolean surgeActive,
      boolean serviceable,
      int pharmaciesCount) {}

  record DemandHour(Instant hour, int orders, int onlineRiders) {}

  Optional<ZoneRow> findById(UUID id);

  /** Zones for admin pricing table (full fee columns). */
  List<ZoneRow> listPricing();

  /** Zone whose polygon covers the point (any serviceability). */
  Optional<ZoneRow> findContaining(double lat, double lng);

  boolean existsNameInCity(String name, String city, UUID excludeId);

  List<ZoneSummaryRow> list(String city, Boolean serviceable, int offset, int limit);

  int count(String city, Boolean serviceable);

  void insert(
      UUID id,
      String name,
      String city,
      String state,
      String wkt,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      int slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      boolean serviceable,
      UUID createdBy,
      Instant now);

  void updateFields(
      UUID id,
      Integer slaMinutes,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      String name,
      String wkt,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      Instant now);

  void updateSurge(UUID id, boolean surgeActive, BigDecimal surgeMultiplier, Instant now);

  void updateServiceable(UUID id, boolean serviceable, String reason, Instant now);

  int countServiceable();

  int countOnlineRiders(UUID zoneId);

  int countOnlineRidersAll();

  int countPharmacies(UUID zoneId);

  List<DemandHour> demandVsSupply(UUID zoneId, Instant from, Instant to);

  BigDecimal avgDeliveryMinutes(UUID zoneId);

  BigDecimal avgDeliveryMinutesAll();

  /** True when lat/lng is inside a serviceable zone polygon matching the pharmacy's zone. */
  boolean isPharmacyAddressServiceable(UUID pharmacyId, double lat, double lng);

  Optional<BigDecimal> minOrderValueForPharmacyAddress(UUID pharmacyId, double lat, double lng);
}
