package com.nammamedmate.inventory.application;

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
public class SupplierRtvService {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public SupplierRtvService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal, UUID grnId, String reason, List<RtvLine> lines) {
    UUID pharmacyId = requirePharmacy(principal);
    if (grnId == null) {
      throw new AppException("VALIDATION_ERROR", "grn_id is required", 400);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    if (lines == null || lines.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "items are required", 400);
    }
    List<Map<String, Object>> grns =
        jdbc.queryForList(
            "SELECT id, status FROM purchase_grn WHERE id = ? AND pharmacy_id = ? AND deleted_at IS NULL",
            grnId,
            pharmacyId);
    if (grns.isEmpty()) {
      throw new AppException("GRN_NOT_FOUND", "GRN not found", 404);
    }
    if (!"STOCKED".equals(String.valueOf(grns.getFirst().get("status")))) {
      throw new AppException("VALIDATION_ERROR", "RTV is only allowed on a stocked GRN", 422);
    }
    Instant now = clock.instant();
    UUID rtvId = Ids.newId();
    String number = "RTV-" + grnId.toString().substring(0, 8).toUpperCase();
    jdbc.update(
        """
        INSERT INTO supplier_rtv (id, pharmacy_id, grn_id, rtv_number, reason, status, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, 'POSTED', ?, ?)
        """,
        rtvId,
        pharmacyId,
        grnId,
        number,
        reason.trim(),
        principal.subject(),
        Timestamp.from(now));
    List<Map<String, Object>> posted = new ArrayList<>();
    for (RtvLine line : lines) {
      if (line == null || line.grnItemId() == null || line.quantity() <= 0) {
        throw new AppException(
            "VALIDATION_ERROR", "Each RTV line needs grn_item_id and quantity", 400);
      }
      List<Map<String, Object>> items =
          jdbc.queryForList(
              """
              SELECT id, product_id, batch_number, quantity, free_quantity
              FROM purchase_grn_item WHERE id = ? AND grn_id = ? AND pharmacy_id = ?
              """,
              line.grnItemId(),
              grnId,
              pharmacyId);
      if (items.isEmpty()) {
        throw new AppException("GRN_ITEM_NOT_FOUND", "GRN item not found", 404);
      }
      Map<String, Object> item = items.getFirst();
      int received =
          ((Number) item.get("quantity")).intValue()
              + ((Number) item.get("free_quantity")).intValue();
      if (line.quantity() > received) {
        throw new AppException("VALIDATION_ERROR", "RTV quantity exceeds GRN quantity", 422);
      }
      UUID productId = (UUID) item.get("product_id");
      String batchNumber = (String) item.get("batch_number");
      List<Map<String, Object>> batches =
          jdbc.queryForList(
              """
              SELECT id FROM product_batch
              WHERE pharmacy_id = ? AND product_id = ? AND batch_number = ?
              """,
              pharmacyId,
              productId,
              batchNumber);
      UUID batchId = batches.isEmpty() ? null : (UUID) batches.getFirst().get("id");
      if (batchId != null) {
        int updated =
            jdbc.update(
                """
                UPDATE product_batch
                SET quantity_current = quantity_current - ?, updated_at = ?
                WHERE id = ? AND pharmacy_id = ? AND quantity_current >= ?
                """,
                line.quantity(),
                Timestamp.from(now),
                batchId,
                pharmacyId,
                line.quantity());
        if (updated != 1) {
          throw new AppException("INSUFFICIENT_STOCK", "Not enough stock to return to vendor", 409);
        }
      }
      jdbc.update(
          """
          INSERT INTO supplier_rtv_item (id, rtv_id, grn_item_id, product_id, batch_id, quantity, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          Ids.newId(),
          rtvId,
          line.grnItemId(),
          productId,
          batchId,
          line.quantity(),
          Timestamp.from(now));
      Map<String, Object> postedLine = new LinkedHashMap<>();
      postedLine.put("grn_item_id", line.grnItemId().toString());
      postedLine.put("quantity", line.quantity());
      posted.add(postedLine);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rtv_id", rtvId.toString());
    data.put("rtv_number", number);
    data.put("grn_id", grnId.toString());
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
  public record RtvLine(UUID grnItemId, int quantity) {}
}
