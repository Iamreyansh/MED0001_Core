package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyRegistrationStore implements PharmacyRegistrationStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPharmacyRegistrationStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(PharmacyRecord pharmacy) {
    String addressJson = writeJson(pharmacy.address());
    jdbc.update(
        """
        INSERT INTO pharmacies (
          id, name, logo_url, city, subscription_plan,
          owner_name, business_name, phone, email, password_hash, business_type, address,
          status, plan, plan_expires_at, gstin, drug_licence_number, licence_state_code,
          fssai_number, pan_number, commission_pct, zone_id, is_online, email_verified,
          can_reapply, created_at, updated_at
        ) VALUES (
          ?, ?, NULL, ?, ?,
          ?, ?, ?, ?, ?, ?, ?::jsonb,
          ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?, ?,
          ?, ?, ?
        )
        """,
        pharmacy.id(),
        pharmacy.name(),
        pharmacy.city(),
        pharmacy.subscriptionPlan(),
        pharmacy.ownerName(),
        pharmacy.businessName(),
        pharmacy.phone(),
        pharmacy.email(),
        pharmacy.passwordHash(),
        pharmacy.businessType(),
        addressJson,
        pharmacy.status(),
        pharmacy.plan(),
        pharmacy.planExpiresAt() == null ? null : Timestamp.from(pharmacy.planExpiresAt()),
        pharmacy.gstin(),
        pharmacy.drugLicenceNumber(),
        pharmacy.licenceStateCode(),
        pharmacy.fssaiNumber(),
        pharmacy.panNumber(),
        pharmacy.commissionPct(),
        pharmacy.zoneId(),
        pharmacy.online(),
        pharmacy.emailVerified(),
        pharmacy.canReapply(),
        Timestamp.from(pharmacy.createdAt()),
        Timestamp.from(pharmacy.updatedAt()));
  }

  @Override
  public Optional<PharmacyRecord> findById(UUID id) {
    List<PharmacyRecord> rows =
        jdbc.query(
            "SELECT * FROM pharmacies WHERE id = ? AND deleted_at IS NULL", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PharmacyRecord> findByEmail(String email) {
    List<PharmacyRecord> rows =
        jdbc.query(
            "SELECT * FROM pharmacies WHERE email = ? AND deleted_at IS NULL", this::mapRow, email);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsGstin(String gstin) {
    return count("SELECT COUNT(*) FROM pharmacies WHERE gstin = ? AND deleted_at IS NULL", gstin)
        > 0;
  }

  @Override
  public boolean existsPan(String pan) {
    return count("SELECT COUNT(*) FROM pharmacies WHERE pan_number = ? AND deleted_at IS NULL", pan)
        > 0;
  }

  @Override
  public boolean existsDrugLicence(String licence, String stateCode) {
    return count(
            """
            SELECT COUNT(*) FROM pharmacies
            WHERE drug_licence_number = ? AND licence_state_code = ? AND deleted_at IS NULL
            """,
            licence,
            stateCode)
        > 0;
  }

  @Override
  public boolean existsPhone(String phone) {
    return count("SELECT COUNT(*) FROM pharmacies WHERE phone = ? AND deleted_at IS NULL", phone)
        > 0;
  }

  @Override
  public boolean existsEmail(String email) {
    return count("SELECT COUNT(*) FROM pharmacies WHERE email = ? AND deleted_at IS NULL", email)
        > 0;
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Override
  public void markEmailVerified(UUID pharmacyId, Instant at) {
    jdbc.update(
        """
        UPDATE pharmacies SET email_verified = TRUE, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(at),
        pharmacyId);
  }

  @Override
  public void updateStatus(
      UUID pharmacyId, String status, Instant kycSubmittedAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET status = ?, kyc_submitted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        status,
        kycSubmittedAt != null ? Timestamp.from(kycSubmittedAt) : null,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  private PharmacyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PharmacyRecord(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("business_name"),
        rs.getString("owner_name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("business_type"),
        readJson(rs.getString("address")),
        rs.getString("status"),
        rs.getString("plan"),
        ts(rs, "plan_expires_at"),
        rs.getString("gstin"),
        rs.getString("drug_licence_number"),
        rs.getString("licence_state_code"),
        rs.getString("fssai_number"),
        rs.getString("pan_number"),
        rs.getBigDecimal("commission_pct") == null
            ? new BigDecimal("8.00")
            : rs.getBigDecimal("commission_pct"),
        (UUID) rs.getObject("zone_id"),
        rs.getBoolean("is_online"),
        rs.getBoolean("email_verified"),
        rs.getBoolean("can_reapply"),
        rs.getString("city"),
        rs.getString("subscription_plan"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"),
        ts(rs, "kyc_submitted_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private String writeJson(Map<String, Object> address) {
    try {
      return objectMapper.writeValueAsString(address == null ? Map.of() : address);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> readJson(String json) {
    if (json == null) {
      return Collections.emptyMap();
    }
    if (json.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
