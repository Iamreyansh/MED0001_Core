package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.EinvoiceIrnRecordStore;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEinvoiceIrnRecordStore implements EinvoiceIrnRecordStore {

  private final JdbcTemplate jdbc;

  public JdbcEinvoiceIrnRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EinvoiceIrnRecord record) {
    jdbc.update(
        """
        INSERT INTO einvoice_irn_records (
          id, pharmacy_id, platform_invoice_id, irn, ack_number, ack_date,
          seller_gstin, buyer_gstin, invoice_number, invoice_date, document_type,
          financial_year, total_invoice_value, qr_code_url, signed_invoice_json,
          status, cancel_reason_code, cancel_remark, generated_at, cancelled_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.platformInvoiceId(),
        record.irn(),
        record.ackNumber(),
        Timestamp.from(record.ackDate()),
        record.sellerGstin(),
        record.buyerGstin(),
        record.invoiceNumber(),
        Date.valueOf(record.invoiceDate()),
        record.documentType(),
        record.financialYear(),
        record.totalInvoiceValue(),
        record.qrCodeUrl(),
        record.signedInvoiceJson(),
        record.status(),
        record.cancelReasonCode(),
        record.cancelRemark(),
        Timestamp.from(record.generatedAt()),
        record.cancelledAt() == null ? null : Timestamp.from(record.cancelledAt()));
  }

  @Override
  public void update(EinvoiceIrnRecord record) {
    jdbc.update(
        """
        UPDATE einvoice_irn_records SET
          status = ?, cancel_reason_code = ?, cancel_remark = ?, cancelled_at = ?
        WHERE id = ?
        """,
        record.status(),
        record.cancelReasonCode(),
        record.cancelRemark(),
        record.cancelledAt() == null ? null : Timestamp.from(record.cancelledAt()),
        record.id());
  }

  @Override
  public Optional<EinvoiceIrnRecord> findByIrn(String irn) {
    List<EinvoiceIrnRecord> rows =
        jdbc.query("SELECT * FROM einvoice_irn_records WHERE irn = ?", this::mapRow, irn);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EinvoiceIrnRecord> findByDocumentKey(
      String sellerGstin,
      String buyerGstin,
      String documentType,
      String financialYear,
      String invoiceNumber) {
    List<EinvoiceIrnRecord> rows =
        jdbc.query(
            """
            SELECT * FROM einvoice_irn_records
            WHERE seller_gstin = ? AND buyer_gstin = ? AND document_type = ?
              AND financial_year = ? AND invoice_number = ?
            """,
            this::mapRow,
            sellerGstin,
            buyerGstin,
            documentType,
            financialYear,
            invoiceNumber);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EinvoiceIrnRecord> findById(UUID id) {
    List<EinvoiceIrnRecord> rows =
        jdbc.query("SELECT * FROM einvoice_irn_records WHERE id = ?", this::mapRow, id);
    return rows.stream().findFirst();
  }

  private EinvoiceIrnRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp cancelled = rs.getTimestamp("cancelled_at");
    return new EinvoiceIrnRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("platform_invoice_id"),
        rs.getString("irn"),
        rs.getString("ack_number"),
        rs.getTimestamp("ack_date").toInstant(),
        rs.getString("seller_gstin"),
        rs.getString("buyer_gstin"),
        rs.getString("invoice_number"),
        rs.getDate("invoice_date").toLocalDate(),
        rs.getString("document_type"),
        rs.getString("financial_year"),
        rs.getBigDecimal("total_invoice_value"),
        rs.getString("qr_code_url"),
        rs.getString("signed_invoice_json"),
        rs.getString("status"),
        rs.getString("cancel_reason_code"),
        rs.getString("cancel_remark"),
        rs.getTimestamp("generated_at").toInstant(),
        cancelled == null ? null : cancelled.toInstant());
  }
}
