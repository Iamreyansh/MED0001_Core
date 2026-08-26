package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcKycDocumentStore implements KycDocumentStore {

  private final JdbcTemplate jdbc;

  public JdbcKycDocumentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(KycDocumentRecord doc) {
    jdbc.update(
        """
        INSERT INTO kyc_documents (
          id, pharmacy_id, document_type, file_key, file_name, file_size_bytes,
          file_mime_type, status, rejection_reason, expiry_date,
          verified_by, verified_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        doc.id(),
        doc.pharmacyId(),
        doc.documentType(),
        doc.fileKey(),
        doc.fileName(),
        doc.fileSizeBytes(),
        doc.fileMimeType(),
        doc.status(),
        doc.rejectionReason(),
        doc.expiryDate() != null ? Date.valueOf(doc.expiryDate()) : null,
        doc.verifiedBy(),
        doc.verifiedAt() != null ? Timestamp.from(doc.verifiedAt()) : null,
        Timestamp.from(doc.createdAt()),
        Timestamp.from(doc.updatedAt()));
  }

  @Override
  public Optional<KycDocumentRecord> findById(UUID docId, UUID pharmacyId) {
    List<KycDocumentRecord> rows =
        jdbc.query(
            "SELECT * FROM kyc_documents WHERE id = ? AND pharmacy_id = ? AND deleted_at IS NULL",
            this::mapRow,
            docId,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<KycDocumentRecord> findByFileKey(String fileKey) {
    List<KycDocumentRecord> rows =
        jdbc.query(
            "SELECT * FROM kyc_documents WHERE file_key = ? AND deleted_at IS NULL",
            this::mapRow,
            fileKey);
    return rows.stream().findFirst();
  }

  @Override
  public List<KycDocumentRecord> findActiveByPharmacy(UUID pharmacyId) {
    return jdbc.query(
        "SELECT * FROM kyc_documents WHERE pharmacy_id = ? AND deleted_at IS NULL ORDER BY created_at ASC",
        this::mapRow,
        pharmacyId);
  }

  @Override
  public void updateStatus(
      UUID docId,
      String status,
      String rejectionReason,
      UUID verifiedBy,
      Instant verifiedAt,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE kyc_documents
        SET status = ?, rejection_reason = ?, verified_by = ?, verified_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        status,
        rejectionReason,
        verifiedBy,
        verifiedAt != null ? Timestamp.from(verifiedAt) : null,
        Timestamp.from(updatedAt),
        docId);
  }

  @Override
  public void softDelete(UUID docId, Instant deletedAt) {
    jdbc.update(
        "UPDATE kyc_documents SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        docId);
  }

  @Override
  public void setAllUploadedToUnderReview(UUID pharmacyId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE kyc_documents
        SET status = 'UNDER_REVIEW', updated_at = ?
        WHERE pharmacy_id = ? AND status IN ('UPLOADED', 'SCAN_CLEAN') AND deleted_at IS NULL
        """,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public int countByPharmacyAndStatuses(UUID pharmacyId, List<String> statuses) {
    if (statuses.isEmpty()) {
      return 0;
    }
    String placeholders = "?,".repeat(statuses.size()).replaceAll(",$", "");
    Object[] args = new Object[1 + statuses.size()];
    args[0] = pharmacyId;
    for (int i = 0; i < statuses.size(); i++) {
      args[i + 1] = statuses.get(i);
    }
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM kyc_documents WHERE pharmacy_id = ? AND status IN ("
                + placeholders
                + ") AND deleted_at IS NULL",
            Integer.class,
            args);
    return n == null ? 0 : n;
  }

  @Override
  public void insertAccessAudit(KycAccessAuditRecord record) {
    jdbc.update(
        "INSERT INTO kyc_document_access_audit (id, document_id, pharmacy_id, admin_id, accessed_at) VALUES (?, ?, ?, ?, ?)",
        record.id(),
        record.documentId(),
        record.pharmacyId(),
        record.adminId(),
        Timestamp.from(record.accessedAt()));
  }

  @Override
  public void insertExpiryAlert(KycExpiryAlertRecord record) {
    jdbc.update(
        "INSERT INTO kyc_expiry_alerts (id, document_id, pharmacy_id, alert_at, template, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        record.id(),
        record.documentId(),
        record.pharmacyId(),
        Timestamp.from(record.alertAt()),
        record.template(),
        Timestamp.from(record.createdAt()));
  }

  @Override
  public boolean existsExpiryAlert(UUID documentId, String template) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM kyc_expiry_alerts WHERE document_id = ? AND template = ?",
            Integer.class,
            documentId,
            template);
    return n != null && n > 0;
  }

  private KycDocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Date expiryDateSql = rs.getDate("expiry_date");
    LocalDate expiryDate = expiryDateSql != null ? expiryDateSql.toLocalDate() : null;
    Timestamp verifiedAtTs = rs.getTimestamp("verified_at");
    return new KycDocumentRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("document_type"),
        rs.getString("file_key"),
        rs.getString("file_name"),
        rs.getLong("file_size_bytes"),
        rs.getString("file_mime_type"),
        rs.getString("status"),
        rs.getString("rejection_reason"),
        expiryDate,
        (UUID) rs.getObject("verified_by"),
        verifiedAtTs != null ? verifiedAtTs.toInstant() : null,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
