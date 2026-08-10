package com.nammamedmate.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.application.port.out.EPrescriptionStore;
import com.nammamedmate.prescription.domain.EPrescriptionRecord;
import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEPrescriptionStore implements EPrescriptionStore {

  private static final TypeReference<List<Map<String, Object>>> MEDS_RAW = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcEPrescriptionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public long nextRxSequence() {
    Long seq = jdbc.queryForObject("SELECT nextval('eprescription_rx_seq')", Long.class);
    return seq == null ? 1L : seq;
  }

  @Override
  public void insert(EPrescriptionRecord r) {
    List<MedicineExtracted> extracted = toExtracted(r.medicines());
    jdbc.update(
        """
        INSERT INTO prescription (
          id, customer_id, type, status, s3_key, file_size_bytes, mime_type,
          patient_name, notes, doctor_name, prescription_date, source,
          medicines_extracted, associated_order_id, teleconsult_id, expires_at,
          rejection_reason, created_at, updated_at, deleted_at,
          rx_id, doctor_id, medicines, is_advice_only, advice_text, clinical_notes,
          digital_signature_hash, is_verified, seal, pdf_s3_key, pdf_generated_at
        ) VALUES (
          ?, ?, 'E_PRESCRIPTION', ?, ?, ?, 'application/pdf',
          ?, NULL, ?, ?, 'TELECONSULT',
          ?::jsonb, NULL, ?, ?,
          NULL, ?, ?, NULL,
          ?, ?, ?::jsonb, ?, ?, ?,
          ?, ?, ?, ?, ?
        )
        """,
        r.id(),
        r.customerId(),
        r.status(),
        r.s3Key(),
        r.fileSizeBytes(),
        r.patientName(),
        r.doctorName(),
        LocalDate.ofInstant(r.issuedAt(), ZoneOffset.UTC),
        toJsonExtracted(extracted),
        r.teleconsultId(),
        Timestamp.from(r.expiresAt()),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.updatedAt()),
        r.rxId(),
        r.doctorId(),
        toJsonMedicines(r.medicines()),
        r.adviceOnly(),
        r.adviceText(),
        r.clinicalNotes(),
        r.digitalSignatureHash(),
        r.verified(),
        r.seal(),
        r.pdfS3Key(),
        r.pdfGeneratedAt() == null ? null : Timestamp.from(r.pdfGeneratedAt()));
  }

  @Override
  public Optional<EPrescriptionRecord> findById(UUID id) {
    List<EPrescriptionRecord> rows =
        jdbc.query(
            """
            SELECT * FROM prescription
            WHERE id = ? AND deleted_at IS NULL AND type = 'E_PRESCRIPTION'
            """,
            this::mapRow,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EPrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId) {
    List<EPrescriptionRecord> rows =
        jdbc.query(
            """
            SELECT * FROM prescription
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL AND type = 'E_PRESCRIPTION'
            """,
            this::mapRow,
            id,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EPrescriptionRecord> findByTeleconsultId(UUID teleconsultId) {
    List<EPrescriptionRecord> rows =
        jdbc.query(
            """
            SELECT * FROM prescription
            WHERE teleconsult_id = ? AND deleted_at IS NULL AND type = 'E_PRESCRIPTION'
            """,
            this::mapRow,
            teleconsultId);
    return rows.stream().findFirst();
  }

  @Override
  public void updatePdf(
      UUID id, String pdfS3Key, long fileSizeBytes, Instant pdfGeneratedAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE prescription
        SET pdf_s3_key = ?, s3_key = ?, file_size_bytes = ?, pdf_generated_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        pdfS3Key,
        pdfS3Key,
        fileSizeBytes,
        Timestamp.from(pdfGeneratedAt),
        Timestamp.from(updatedAt),
        id);
  }

  private EPrescriptionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Instant created = rs.getTimestamp("created_at").toInstant();
    return new EPrescriptionRecord(
        (UUID) rs.getObject("id"),
        rs.getString("rx_id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("teleconsult_id"),
        (UUID) rs.getObject("doctor_id"),
        rs.getString("doctor_name"),
        rs.getString("patient_name"),
        parseMedicines(rs.getString("medicines")),
        rs.getBoolean("is_advice_only"),
        rs.getString("advice_text"),
        rs.getString("clinical_notes"),
        rs.getString("digital_signature_hash"),
        rs.getBoolean("is_verified"),
        rs.getString("seal"),
        rs.getString("status"),
        rs.getString("s3_key"),
        rs.getString("pdf_s3_key"),
        rs.getTimestamp("pdf_generated_at") == null
            ? null
            : rs.getTimestamp("pdf_generated_at").toInstant(),
        rs.getLong("file_size_bytes"),
        (UUID) rs.getObject("associated_order_id"),
        created,
        rs.getTimestamp("expires_at").toInstant(),
        created,
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }

  private List<MedicinePrescribed> parseMedicines(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, MEDS_RAW);
      List<MedicinePrescribed> out = new ArrayList<>();
      for (Map<String, Object> row : raw) {
        out.add(
            new MedicinePrescribed(
                str(row.get("name")),
                str(row.get("dosage")),
                str(row.get("frequency")),
                intVal(row.get("quantity")),
                str(row.get("unit")),
                row.get("duration_days") == null ? null : intVal(row.get("duration_days")),
                row.get("notes") == null ? null : str(row.get("notes"))));
      }
      return out;
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  static int intVal(Object o) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    if (o == null) {
      return 0;
    }
    try {
      return Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private String toJsonMedicines(List<MedicinePrescribed> medicines) {
    List<Map<String, Object>> maps = medicines.stream().map(MedicinePrescribed::toApiMap).toList();
    return toJson(maps);
  }

  private String toJsonExtracted(List<MedicineExtracted> medicines) {
    if (medicines.isEmpty()) {
      return null;
    }
    return toJson(medicines);
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize json", e);
    }
  }

  private static List<MedicineExtracted> toExtracted(List<MedicinePrescribed> medicines) {
    if (medicines.isEmpty()) {
      return List.of();
    }
    List<MedicineExtracted> out = new ArrayList<>();
    for (MedicinePrescribed m : medicines) {
      String qty = m.quantity() + (m.unit() == null || m.unit().isBlank() ? "" : " " + m.unit());
      out.add(new MedicineExtracted(m.name(), qty, m.dosage(), m.frequency()));
    }
    return out;
  }
}
