package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyProfileStore implements PharmacyProfileStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPharmacyProfileStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<ProfileRecord> findById(UUID pharmacyId) {
    List<ProfileRecord> rows =
        jdbc.query(
            """
            SELECT id, code, business_name, tagline, logo_url, phone, email, pending_phone, pending_email,
                   business_type, address, status, plan, gstin, pan_number, drug_licence_number, fssai_number,
                   is_gst_registered, e_invoicing_enabled, tds_applicable, tcs_applicable,
                   gstin_reverification_pending, registered_pharmacist_name, created_at, updated_at
            FROM pharmacies WHERE id = ? AND deleted_at IS NULL
            """,
            this::mapProfile,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void updateProfileFields(
      UUID pharmacyId,
      String tagline,
      String logoUrl,
      Map<String, Object> address,
      Instant updatedAt) {
    if (address != null) {
      jdbc.update(
          """
          UPDATE pharmacies SET tagline = COALESCE(?, tagline), logo_url = COALESCE(?, logo_url),
            address = ?::jsonb, updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          tagline,
          logoUrl,
          writeJson(address),
          Timestamp.from(updatedAt),
          pharmacyId);
    } else {
      jdbc.update(
          """
          UPDATE pharmacies SET tagline = COALESCE(?, tagline), logo_url = COALESCE(?, logo_url),
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          tagline,
          logoUrl,
          Timestamp.from(updatedAt),
          pharmacyId);
    }
  }

  @Override
  public void setPendingPhone(UUID pharmacyId, String pendingPhone, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET pending_phone = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        pendingPhone,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void setPendingEmail(UUID pharmacyId, String pendingEmail, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET pending_email = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        pendingEmail,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void applyPhone(UUID pharmacyId, String phone, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET phone = ?, pending_phone = NULL, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        phone,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void applyEmail(UUID pharmacyId, String email, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET email = ?, pending_email = NULL, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        email,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateTaxFields(
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
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          gstin = COALESCE(?, gstin),
          pan_number = COALESCE(?, pan_number),
          drug_licence_number = COALESCE(?, drug_licence_number),
          fssai_number = COALESCE(?, fssai_number),
          is_gst_registered = COALESCE(?, is_gst_registered),
          e_invoicing_enabled = COALESCE(?, e_invoicing_enabled),
          tds_applicable = COALESCE(?, tds_applicable),
          tcs_applicable = COALESCE(?, tcs_applicable),
          registered_pharmacist_name = COALESCE(?, registered_pharmacist_name),
          gstin_reverification_pending = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        gstin,
        panNumber,
        drugLicenceNumber,
        fssaiNumber,
        isGstRegistered,
        eInvoicingEnabled,
        tdsApplicable,
        tcsApplicable,
        registeredPharmacistName,
        gstinReverificationPending,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateBusinessName(UUID pharmacyId, String businessName, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET business_name = ?, name = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        businessName,
        businessName,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateTagline(UUID pharmacyId, String tagline, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET tagline = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        tagline,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateLogoUrl(UUID pharmacyId, String logoUrl, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET logo_url = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        logoUrl,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateAddress(UUID pharmacyId, Map<String, Object> address, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET address = ?::jsonb, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        writeJson(address),
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updatePhone(UUID pharmacyId, String phone, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET phone = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        phone,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateEmail(UUID pharmacyId, String email, Instant updatedAt) {
    jdbc.update(
        "UPDATE pharmacies SET email = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        email,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void replaceOperatingHours(
      UUID pharmacyId, List<OperatingHoursRecord> hours, Instant now) {
    jdbc.update("DELETE FROM pharmacy_operating_hours WHERE pharmacy_id = ?", pharmacyId);
    for (OperatingHoursRecord h : hours) {
      jdbc.update(
          """
          INSERT INTO pharmacy_operating_hours (
            id, pharmacy_id, day_of_week, open_time, close_time, is_closed, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          h.id(),
          pharmacyId,
          h.dayOfWeek(),
          h.openTime() == null ? null : Time.valueOf(h.openTime()),
          h.closeTime() == null ? null : Time.valueOf(h.closeTime()),
          h.closed(),
          Timestamp.from(now),
          Timestamp.from(now));
    }
  }

  @Override
  public List<OperatingHoursRecord> listOperatingHours(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT id, pharmacy_id, day_of_week, open_time, close_time, is_closed
        FROM pharmacy_operating_hours WHERE pharmacy_id = ? ORDER BY day_of_week
        """,
        (rs, n) ->
            new OperatingHoursRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("pharmacy_id"),
                rs.getInt("day_of_week"),
                toLocalTime(rs.getTime("open_time")),
                toLocalTime(rs.getTime("close_time")),
                rs.getBoolean("is_closed")),
        pharmacyId);
  }

  @Override
  public Optional<BankAccountRecord> findActiveBankAccount(UUID pharmacyId) {
    List<BankAccountRecord> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_bank_accounts
            WHERE pharmacy_id = ? AND deleted_at IS NULL
            ORDER BY created_at DESC LIMIT 1
            """,
            this::mapBank,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void softDeleteBankAccount(UUID bankAccountId, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_bank_accounts SET deleted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        bankAccountId);
  }

  @Override
  public void insertBankAccount(BankAccountRecord record) {
    jdbc.update(
        """
        INSERT INTO pharmacy_bank_accounts (
          id, pharmacy_id, account_holder, bank_name, account_number_encrypted, account_number_last4,
          ifsc_code, account_type, verification_status, penny_drop_reference, verified_at,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.accountHolder(),
        record.bankName(),
        record.accountNumberEncrypted(),
        record.accountNumberLast4(),
        record.ifscCode(),
        record.accountType(),
        record.verificationStatus(),
        record.pennyDropReference(),
        record.verifiedAt() == null ? null : Timestamp.from(record.verifiedAt()),
        Timestamp.from(record.createdAt()),
        Timestamp.from(record.updatedAt()));
  }

  @Override
  public void updateBankVerification(
      UUID bankAccountId,
      String verificationStatus,
      String pennyDropReference,
      Instant verifiedAt,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_bank_accounts SET
          verification_status = ?, penny_drop_reference = COALESCE(?, penny_drop_reference),
          verified_at = ?, updated_at = ?
        WHERE id = ?
        """,
        verificationStatus,
        pennyDropReference,
        verifiedAt == null ? null : Timestamp.from(verifiedAt),
        Timestamp.from(updatedAt),
        bankAccountId);
  }

  @Override
  public List<BankAccountRecord> findStalePendingBankAccounts(Instant createdBefore, int limit) {
    return jdbc.query(
        """
        SELECT * FROM pharmacy_bank_accounts
        WHERE verification_status = 'PENDING' AND created_at < ? AND deleted_at IS NULL
        ORDER BY created_at ASC LIMIT ?
        """,
        this::mapBank,
        Timestamp.from(createdBefore),
        limit);
  }

  private ProfileRecord mapProfile(ResultSet rs, int rowNum) throws SQLException {
    return new ProfileRecord(
        (UUID) rs.getObject("id"),
        rs.getString("code"),
        rs.getString("business_name"),
        rs.getString("tagline"),
        rs.getString("logo_url"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("pending_phone"),
        rs.getString("pending_email"),
        rs.getString("business_type"),
        readJson(rs.getString("address")),
        rs.getString("status"),
        rs.getString("plan"),
        rs.getString("gstin"),
        rs.getString("pan_number"),
        rs.getString("drug_licence_number"),
        rs.getString("fssai_number"),
        rs.getBoolean("is_gst_registered"),
        rs.getBoolean("e_invoicing_enabled"),
        rs.getBoolean("tds_applicable"),
        rs.getBoolean("tcs_applicable"),
        rs.getBoolean("gstin_reverification_pending"),
        rs.getString("registered_pharmacist_name"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private BankAccountRecord mapBank(ResultSet rs, int rowNum) throws SQLException {
    return new BankAccountRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("account_holder"),
        rs.getString("bank_name"),
        rs.getString("account_number_encrypted"),
        rs.getString("account_number_last4"),
        rs.getString("ifsc_code"),
        rs.getString("account_type"),
        rs.getString("verification_status"),
        rs.getString("penny_drop_reference"),
        ts(rs, "verified_at"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static LocalTime toLocalTime(Time t) {
    return t == null ? null : t.toLocalTime();
  }

  private String writeJson(Map<String, Object> address) {
    try {
      return objectMapper.writeValueAsString(address == null ? Map.of() : address);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> readJson(String json) {
    if (json == null || json.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
