package com.nammamedmate.pos.application;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosReturnService {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public PosReturnService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> createReturn(
      MedmatePrincipal principal, UUID invoiceId, String reason, List<ReturnLine> lines) {
    UUID pharmacyId = requirePharmacy(principal);
    if (invoiceId == null) {
      throw new AppException("VALIDATION_ERROR", "invoice_id is required", 400);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    if (lines == null || lines.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "items are required", 400);
    }
    List<Map<String, Object>> invoices =
        jdbc.queryForList(
            "SELECT id, status FROM invoice WHERE id = ? AND pharmacy_id = ?",
            invoiceId,
            pharmacyId);
    if (invoices.isEmpty()) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404);
    }
    if ("CREDIT_NOTE_ISSUED".equals(String.valueOf(invoices.getFirst().get("status")))) {
      throw new AppException("INVOICE_IMMUTABLE", "A credit note was already issued", 409);
    }
    Instant now = clock.instant();
    UUID noteId = Ids.newId();
    String number = "CN-" + invoiceId.toString().substring(0, 8).toUpperCase();
    jdbc.update(
        """
        INSERT INTO invoice_credit_note (
          id, pharmacy_id, invoice_id, credit_note_number, reason, total_paise, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, 0, ?, ?)
        """,
        noteId,
        pharmacyId,
        invoiceId,
        number,
        reason.trim(),
        principal.subject(),
        Timestamp.from(now));
    long total = 0L;
    List<Map<String, Object>> posted = new ArrayList<>();
    for (ReturnLine line : lines) {
      if (line == null || line.invoiceItemId() == null || line.quantity() <= 0) {
        throw new AppException(
            "VALIDATION_ERROR", "Each return line needs invoice_item_id and quantity", 400);
      }
      List<Map<String, Object>> items =
          jdbc.queryForList(
              """
              SELECT id, product_id, batch_id, quantity, line_total_paise
              FROM invoice_item WHERE id = ? AND invoice_id = ?
              """,
              line.invoiceItemId(),
              invoiceId);
      if (items.isEmpty()) {
        throw new AppException("INVOICE_ITEM_NOT_FOUND", "Invoice item not found", 404);
      }
      Map<String, Object> item = items.getFirst();
      int original = ((Number) item.get("quantity")).intValue();
      if (line.quantity() > original) {
        throw new AppException("VALIDATION_ERROR", "Return quantity exceeds billed quantity", 422);
      }
      long lineTotal =
          ((Number) item.get("line_total_paise")).longValue() * line.quantity() / original;
      total += lineTotal;
      UUID productId = (UUID) item.get("product_id");
      UUID batchId = (UUID) item.get("batch_id");
      jdbc.update(
          """
          INSERT INTO invoice_credit_note_item (
            id, credit_note_id, invoice_item_id, product_id, batch_id, quantity, line_total_paise, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          Ids.newId(),
          noteId,
          line.invoiceItemId(),
          productId,
          batchId,
          line.quantity(),
          lineTotal,
          Timestamp.from(now));
      if (batchId != null) {
        jdbc.update(
            """
            UPDATE product_batch
            SET quantity_current = quantity_current + ?, is_active = TRUE, updated_at = ?
            WHERE id = ? AND pharmacy_id = ?
            """,
            line.quantity(),
            Timestamp.from(now),
            batchId,
            pharmacyId);
      }
      Map<String, Object> postedLine = new LinkedHashMap<>();
      postedLine.put("invoice_item_id", line.invoiceItemId().toString());
      postedLine.put("quantity", line.quantity());
      postedLine.put("line_total_paise", lineTotal);
      posted.add(postedLine);
    }
    jdbc.update("UPDATE invoice_credit_note SET total_paise = ? WHERE id = ?", total, noteId);
    jdbc.update(
        "UPDATE invoice SET status = 'CREDIT_NOTE_ISSUED' WHERE id = ? AND pharmacy_id = ?",
        invoiceId,
        pharmacyId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("credit_note_id", noteId.toString());
    data.put("credit_note_number", number);
    data.put("invoice_id", invoiceId.toString());
    data.put("total_paise", total);
    data.put("items", posted);
    return data;
  }

  private static UUID requirePharmacy(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
    return principal.pharmacyId();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReturnLine(UUID invoiceItemId, int quantity) {}
}
