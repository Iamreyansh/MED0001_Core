package com.nammamedmate.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPrescriptionStore implements PrescriptionStore {

  private static final TypeReference<List<MedicineExtracted>> MEDS_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPrescriptionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(PrescriptionRecord r) {
    jdbc.update(
        """
        INSERT INTO prescription (
          id, customer_id, type, status, s3_key, file_size_bytes, mime_type,
          patient_name, notes, doctor_name, prescription_date, source,
          medicines_extracted, associated_order_id, teleconsult_id, expires_at,
          rejection_reason, created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?::jsonb, ?, ?, ?,
          ?, ?, ?, ?
        )
        """,
        r.id(),
        r.customerId(),
        r.type(),
        r.status(),
        r.s3Key(),
        r.fileSizeBytes(),
        r.mimeType(),
        r.patientName(),
        r.notes(),
        r.doctorName(),
        r.prescriptionDate() == null ? null : Date.valueOf(r.prescriptionDate()),
        r.source(),
        toJson(r.medicinesExtracted()),
        r.associatedOrderId(),
        r.teleconsultId(),
        Timestamp.from(r.expiresAt()),
        r.rejectionReason(),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.updatedAt()),
        r.deletedAt() == null ? null : Timestamp.from(r.deletedAt()));
  }

  @Override
  public Optional<PrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId) {
    List<PrescriptionRecord> rows =
        jdbc.query(
            """
            SELECT * FROM prescription
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            this::mapRow,
            id,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PrescriptionRecord> findById(UUID id) {
    List<PrescriptionRecord> rows =
        jdbc.query(
            "SELECT * FROM prescription WHERE id = ? AND deleted_at IS NULL", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public Page listForCustomer(
      UUID customerId, String status, String type, int page, int limit, String sort, String order) {
    // ponytail: only created_at sort is supported in STORY-001
    String sortCol = "created_at";
    String dir = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE customer_id = ? AND deleted_at IS NULL ");
    args.add(customerId);
    if (status != null) {
      where.append(" AND status = ? ");
      args.add(status);
    }
    if (type != null) {
      where.append(" AND type = ? ");
      args.add(type);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM prescription" + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;
    int offset = (page - 1) * limit;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<PrescriptionRecord> items =
        jdbc.query(
            "SELECT * FROM prescription"
                + where
                + " ORDER BY "
                + sortCol
                + " "
                + dir
                + " LIMIT ? OFFSET ?",
            this::mapRow,
            pageArgs.toArray());
    return new Page(items, totalCount);
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE prescription
        SET deleted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void updateOcr(
      UUID id,
      String doctorName,
      LocalDate prescriptionDate,
      List<MedicineExtracted> medicines,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE prescription
        SET doctor_name = ?, prescription_date = ?, medicines_extracted = ?::jsonb, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        doctorName,
        prescriptionDate == null ? null : Date.valueOf(prescriptionDate),
        toJson(medicines),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void updateStatus(UUID id, String status, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE prescription
        SET status = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        status,
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public int markExpiredDue(Instant now, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE prescription
        SET status = 'EXPIRED', updated_at = ?
        WHERE deleted_at IS NULL
          AND status NOT IN ('EXPIRED', 'DISPENSED')
          AND expires_at <= ?
        """,
        Timestamp.from(updatedAt),
        Timestamp.from(now));
  }

  private PrescriptionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PrescriptionRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("type"),
        rs.getString("status"),
        rs.getString("s3_key"),
        rs.getLong("file_size_bytes"),
        rs.getString("mime_type"),
        rs.getString("patient_name"),
        rs.getString("notes"),
        rs.getString("doctor_name"),
        rs.getDate("prescription_date") == null
            ? null
            : rs.getDate("prescription_date").toLocalDate(),
        rs.getString("source"),
        parseMeds(rs.getString("medicines_extracted")),
        (UUID) rs.getObject("associated_order_id"),
        (UUID) rs.getObject("teleconsult_id"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("rejection_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }

  private String toJson(List<MedicineExtracted> medicines) {
    if (medicines == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(medicines);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize medicines_extracted", e);
    }
  }

  private List<MedicineExtracted> parseMeds(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MEDS_TYPE);
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
