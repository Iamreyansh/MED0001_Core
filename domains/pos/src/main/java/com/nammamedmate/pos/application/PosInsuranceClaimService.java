package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosInsuranceClaimService {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public PosInsuranceClaimService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> submit(
      MedmatePrincipal principal,
      UUID invoiceId,
      String tpaName,
      String policyNumber,
      String notes) {
    UUID pharmacyId = requirePharmacy(principal);
    if (invoiceId == null) {
      throw new AppException("VALIDATION_ERROR", "invoice_id is required", 400);
    }
    List<Map<String, Object>> invoices =
        jdbc.queryForList(
            "SELECT id, payment_method FROM invoice WHERE id = ? AND pharmacy_id = ?",
            invoiceId,
            pharmacyId);
    if (invoices.isEmpty()) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404);
    }
    if (!"INSURANCE_TPA".equals(String.valueOf(invoices.getFirst().get("payment_method")))) {
      throw new AppException("VALIDATION_ERROR", "Invoice is not an insurance/TPA sale", 422);
    }
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT id, status FROM pos_insurance_claim WHERE invoice_id = ?", invoiceId);
    if (!existing.isEmpty()) {
      Map<String, Object> row = existing.getFirst();
      return Map.of(
          "claim_id", row.get("id").toString(),
          "status", row.get("status"),
          "invoice_id", invoiceId.toString());
    }
    Instant now = clock.instant();
    UUID id = Ids.newId();
    jdbc.update(
        """
        INSERT INTO pos_insurance_claim (
          id, pharmacy_id, invoice_id, tpa_name, policy_number, status, notes, created_by, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?)
        """,
        id,
        pharmacyId,
        invoiceId,
        blankToNull(tpaName),
        blankToNull(policyNumber),
        blankToNull(notes),
        principal.subject(),
        Timestamp.from(now),
        Timestamp.from(now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("claim_id", id.toString());
    data.put("invoice_id", invoiceId.toString());
    data.put("status", "SUBMITTED");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID invoiceId) {
    UUID pharmacyId = requirePharmacy(principal);
    if (invoiceId == null) {
      throw new AppException("VALIDATION_ERROR", "invoice_id is required", 400);
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT id, tpa_name, policy_number, status, notes, created_at
            FROM pos_insurance_claim WHERE invoice_id = ? AND pharmacy_id = ?
            """,
            invoiceId,
            pharmacyId);
    if (rows.isEmpty()) {
      throw new AppException("CLAIM_NOT_FOUND", "No TPA claim for this invoice", 404);
    }
    Map<String, Object> row = rows.getFirst();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("claim_id", row.get("id").toString());
    data.put("invoice_id", invoiceId.toString());
    data.put("tpa_name", row.get("tpa_name"));
    data.put("policy_number", row.get("policy_number"));
    data.put("status", row.get("status"));
    data.put("notes", row.get("notes"));
    return data;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
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
}
