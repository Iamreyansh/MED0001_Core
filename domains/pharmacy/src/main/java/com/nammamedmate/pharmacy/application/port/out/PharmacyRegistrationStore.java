package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyRegistrationStore {

  record PharmacyRecord(
      UUID id,
      String name,
      String businessName,
      String ownerName,
      String phone,
      String email,
      String passwordHash,
      String businessType,
      Map<String, Object> address,
      String status,
      String plan,
      Instant planExpiresAt,
      String gstin,
      String drugLicenceNumber,
      String licenceStateCode,
      String fssaiNumber,
      String panNumber,
      BigDecimal commissionPct,
      UUID zoneId,
      boolean online,
      boolean emailVerified,
      boolean canReapply,
      String city,
      String subscriptionPlan,
      Instant createdAt,
      Instant updatedAt) {
    public PharmacyRecord {
      address = address == null ? null : Map.copyOf(address);
    }
  }

  void insert(PharmacyRecord pharmacy);

  Optional<PharmacyRecord> findById(UUID id);

  Optional<PharmacyRecord> findByEmail(String email);

  boolean existsGstin(String gstin);

  boolean existsPan(String pan);

  boolean existsDrugLicence(String licence, String stateCode);

  boolean existsPhone(String phone);

  boolean existsEmail(String email);

  void markEmailVerified(UUID pharmacyId, Instant at);
}
