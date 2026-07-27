package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyProfileStore {

  record ProfileRecord(
      UUID id,
      String code,
      String businessName,
      String tagline,
      String logoUrl,
      String phone,
      String email,
      String pendingPhone,
      String pendingEmail,
      String businessType,
      Map<String, Object> address,
      String status,
      String plan,
      String gstin,
      String panNumber,
      String drugLicenceNumber,
      String fssaiNumber,
      boolean isGstRegistered,
      boolean eInvoicingEnabled,
      boolean tdsApplicable,
      boolean tcsApplicable,
      boolean gstinReverificationPending,
      String registeredPharmacistName,
      Instant createdAt,
      Instant updatedAt) {
    public ProfileRecord {
      address = address == null ? Map.of() : Map.copyOf(address);
    }
  }

  record OperatingHoursRecord(
      UUID id,
      UUID pharmacyId,
      int dayOfWeek,
      LocalTime openTime,
      LocalTime closeTime,
      boolean closed) {}

  record BankAccountRecord(
      UUID id,
      UUID pharmacyId,
      String accountHolder,
      String bankName,
      String accountNumberEncrypted,
      String accountNumberLast4,
      String ifscCode,
      String accountType,
      String verificationStatus,
      String pennyDropReference,
      Instant verifiedAt,
      Instant createdAt,
      Instant updatedAt) {}

  Optional<ProfileRecord> findById(UUID pharmacyId);

  void updateProfileFields(
      UUID pharmacyId,
      String tagline,
      String logoUrl,
      Map<String, Object> address,
      Instant updatedAt);

  void setPendingPhone(UUID pharmacyId, String pendingPhone, Instant updatedAt);

  void setPendingEmail(UUID pharmacyId, String pendingEmail, Instant updatedAt);

  void applyPhone(UUID pharmacyId, String phone, Instant updatedAt);

  void applyEmail(UUID pharmacyId, String email, Instant updatedAt);

  void updateTaxFields(
      UUID pharmacyId,
      String gstin,
      String panNumber,
      String drugLicenceNumber,
      String fssaiNumber,
      Boolean isGstRegistered,
      Boolean eInvoicingEnabled,
      Boolean tdsApplicable,
      Boolean tcsApplicable,
      String registeredPharmacistName,
      boolean gstinReverificationPending,
      Instant updatedAt);

  void replaceOperatingHours(UUID pharmacyId, List<OperatingHoursRecord> hours, Instant now);

  List<OperatingHoursRecord> listOperatingHours(UUID pharmacyId);

  Optional<BankAccountRecord> findActiveBankAccount(UUID pharmacyId);

  void softDeleteBankAccount(UUID bankAccountId, Instant deletedAt);

  void insertBankAccount(BankAccountRecord record);

  void updateBankVerification(
      UUID bankAccountId,
      String verificationStatus,
      String pennyDropReference,
      Instant verifiedAt,
      Instant updatedAt);

  List<BankAccountRecord> findStalePendingBankAccounts(Instant createdBefore, int limit);

  void updateBusinessName(UUID pharmacyId, String businessName, Instant updatedAt);

  void updateTagline(UUID pharmacyId, String tagline, Instant updatedAt);

  void updateLogoUrl(UUID pharmacyId, String logoUrl, Instant updatedAt);

  void updateAddress(UUID pharmacyId, Map<String, Object> address, Instant updatedAt);

  void updatePhone(UUID pharmacyId, String phone, Instant updatedAt);

  void updateEmail(UUID pharmacyId, String email, Instant updatedAt);
}
