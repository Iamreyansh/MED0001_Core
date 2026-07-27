package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore;
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
public class JdbcKycVerificationStore implements KycVerificationStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> FLAG_LIST =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcKycVerificationStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(KycVerificationRecord record) {
    jdbc.update(
        """
        INSERT INTO kyc_verifications (
          id, pharmacy_id, job_id, verification_type, api_provider, request_payload,
          response_payload, status, details, admin_flags, retry_count, next_retry_at,
          verified_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.jobId(),
        record.verificationType(),
        record.apiProvider(),
        writeJson(record.requestPayload()),
        record.responsePayload() == null ? null : writeJson(record.responsePayload()),
        record.status(),
        record.details() == null ? null : writeJson(record.details()),
        writeJson(record.adminFlags()),
        record.retryCount(),
        record.nextRetryAt() == null ? null : Timestamp.from(record.nextRetryAt()),
        record.verifiedAt() == null ? null : Timestamp.from(record.verifiedAt()),
        Timestamp.from(record.createdAt()));
  }

  @Override
  public Optional<KycVerificationRecord> findById(UUID id) {
    List<KycVerificationRecord> rows =
        jdbc.query("SELECT * FROM kyc_verifications WHERE id = ?", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public List<KycVerificationRecord> findByJobId(UUID jobId) {
    return jdbc.query(
        """
        SELECT * FROM kyc_verifications
        WHERE job_id = ?
        ORDER BY created_at ASC
        """,
        this::mapRow,
        jobId);
  }

  @Override
  public Optional<KycVerificationRecord> findByJobAndType(UUID jobId, String verificationType) {
    List<KycVerificationRecord> rows =
        jdbc.query(
            """
            SELECT * FROM kyc_verifications
            WHERE job_id = ? AND verification_type = ?
            """,
            this::mapRow,
            jobId,
            verificationType);
    return rows.stream().findFirst();
  }

  @Override
  public void updateResult(
      UUID id,
      String status,
      Map<String, Object> responsePayload,
      Map<String, Object> details,
      List<Map<String, Object>> adminFlags,
      int retryCount,
      Instant nextRetryAt,
      Instant verifiedAt) {
    jdbc.update(
        """
        UPDATE kyc_verifications SET
          status = ?,
          response_payload = ?::jsonb,
          details = ?::jsonb,
          admin_flags = ?::jsonb,
          retry_count = ?,
          next_retry_at = ?,
          verified_at = ?
        WHERE id = ?
        """,
        status,
        responsePayload == null ? null : writeJson(responsePayload),
        details == null ? null : writeJson(details),
        writeJson(adminFlags),
        retryCount,
        nextRetryAt == null ? null : Timestamp.from(nextRetryAt),
        verifiedAt == null ? null : Timestamp.from(verifiedAt),
        id);
  }

  @Override
  public List<KycVerificationRecord> findDueRetries(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM kyc_verifications
        WHERE status = 'ERROR' AND next_retry_at IS NOT NULL AND next_retry_at <= ?
        ORDER BY next_retry_at ASC
        LIMIT ?
        """,
        this::mapRow,
        Timestamp.from(now),
        limit);
  }

  private KycVerificationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new KycVerificationRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("job_id"),
        rs.getString("verification_type"),
        rs.getString("api_provider"),
        readJsonMap(rs.getString("request_payload")),
        readJsonMapNullable(rs.getString("response_payload")),
        rs.getString("status"),
        readJsonMapNullable(rs.getString("details")),
        readFlags(rs.getString("admin_flags")),
        rs.getInt("retry_count"),
        ts(rs, "next_retry_at"),
        ts(rs, "verified_at"),
        ts(rs, "created_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private Map<String, Object> readJsonMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> readJsonMapNullable(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return readJsonMap(json);
  }

  private List<Map<String, Object>> readFlags(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> flags = objectMapper.readValue(json, FLAG_LIST);
      return flags == null ? List.of() : List.copyOf(flags);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
