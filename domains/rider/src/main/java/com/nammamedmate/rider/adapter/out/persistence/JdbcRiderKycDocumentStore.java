package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
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
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderKycDocumentStore implements RiderKycDocumentStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderKycDocumentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(DocumentRecord doc) {
    jdbc.update(
        """
        INSERT INTO rider_kyc_documents (
          id, rider_id, document_type, document_number, file_key, file_url,
          file_size_bytes, mime_type, expiry_date, expiry_alert_sent,
          verification_status, rejection_reason, uploaded_at, reviewed_at, reviewed_by
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        doc.id(),
        doc.riderId(),
        doc.documentType(),
        doc.documentNumber(),
        doc.fileKey(),
        doc.fileUrl(),
        doc.fileSizeBytes(),
        doc.mimeType(),
        doc.expiryDate() == null ? null : Date.valueOf(doc.expiryDate()),
        doc.expiryAlertSent(),
        doc.verificationStatus(),
        doc.rejectionReason(),
        Timestamp.from(doc.uploadedAt()),
        doc.reviewedAt() == null ? null : Timestamp.from(doc.reviewedAt()),
        doc.reviewedBy());
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt) {
    jdbc.update(
        "UPDATE rider_kyc_documents SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL",
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType) {
    List<DocumentRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_kyc_documents
            WHERE rider_id = ? AND document_type = ? AND deleted_at IS NULL
            ORDER BY uploaded_at DESC LIMIT 1
            """,
            this::map,
            riderId,
            documentType);
    return rows.stream().findFirst();
  }

  @Override
  public List<DocumentRecord> findActiveByRider(UUID riderId) {
    return jdbc.query(
        """
        SELECT * FROM rider_kyc_documents
        WHERE rider_id = ? AND deleted_at IS NULL
        ORDER BY uploaded_at ASC
        """,
        this::map,
        riderId);
  }

  @Override
  public int countUploadsByRiderAndType(UUID riderId, String documentType) {
    // Count all historical uploads (including soft-deleted) for the 5-slot cap
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM rider_kyc_documents
            WHERE rider_id = ? AND document_type = ?
            """,
            Integer.class,
            riderId,
            documentType);
    return count == null ? 0 : count;
  }

  @Override
  public List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after) {
    return jdbc.query(
        """
        SELECT * FROM rider_kyc_documents
        WHERE deleted_at IS NULL
          AND expiry_alert_sent = FALSE
          AND document_type IN ('VEHICLE_INSURANCE', 'PUC_CERTIFICATE')
          AND expiry_date IS NOT NULL
          AND expiry_date > ?
          AND expiry_date <= ?
        """,
        this::map,
        Date.valueOf(after),
        Date.valueOf(onOrBefore));
  }

  @Override
  public void markExpiryAlertSent(UUID documentId) {
    jdbc.update("UPDATE rider_kyc_documents SET expiry_alert_sent = TRUE WHERE id = ?", documentId);
  }

  private DocumentRecord map(ResultSet rs, int rowNum) throws SQLException {
    Date expiry = rs.getDate("expiry_date");
    Timestamp uploaded = rs.getTimestamp("uploaded_at");
    Timestamp reviewed = rs.getTimestamp("reviewed_at");
    return new DocumentRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        rs.getString("document_type"),
        rs.getString("document_number"),
        rs.getString("file_key"),
        rs.getString("file_url"),
        rs.getInt("file_size_bytes"),
        rs.getString("mime_type"),
        expiry == null ? null : expiry.toLocalDate(),
        rs.getBoolean("expiry_alert_sent"),
        rs.getString("verification_status"),
        rs.getString("rejection_reason"),
        uploaded.toInstant(),
        reviewed == null ? null : reviewed.toInstant(),
        (UUID) rs.getObject("reviewed_by"));
  }
}
