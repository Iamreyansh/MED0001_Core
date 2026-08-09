package com.nammamedmate.pos.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pos.application.port.out.InvoiceSettingsStore;
import com.nammamedmate.pos.domain.InvoiceSettings;
import com.nammamedmate.pos.domain.InvoiceTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvoiceSettingsStore implements InvoiceSettingsStore {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RowMapper<InvoiceSettings> mapper = this::mapRow;

  public JdbcInvoiceSettingsStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public InvoiceSettings getOrCreate(UUID pharmacyId) {
    List<InvoiceSettings> existing =
        jdbc.query("SELECT * FROM invoice_settings WHERE pharmacy_id = ?", mapper, pharmacyId);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    Instant now = Instant.now();
    jdbc.update(
        """
        INSERT INTO invoice_settings (pharmacy_id, updated_at)
        VALUES (?, ?)
        ON CONFLICT (pharmacy_id) DO NOTHING
        """,
        pharmacyId,
        Timestamp.from(now));
    List<InvoiceSettings> created =
        jdbc.query("SELECT * FROM invoice_settings WHERE pharmacy_id = ?", mapper, pharmacyId);
    if (!created.isEmpty()) {
      return created.getFirst();
    }
    return defaults(pharmacyId, now);
  }

  @Override
  public InvoiceSettings upsert(InvoiceSettings settings) {
    jdbc.update(
        """
        INSERT INTO invoice_settings (
          pharmacy_id, template, accent_color, logo_url, signature_url, document_title,
          invoice_prefix, signatory_label, bank_details, terms_and_conditions, footer_note,
          show_mrp_savings, show_doctor, show_hsn, print_bank_details, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
        ON CONFLICT (pharmacy_id) DO UPDATE SET
          template = EXCLUDED.template,
          accent_color = EXCLUDED.accent_color,
          logo_url = EXCLUDED.logo_url,
          signature_url = EXCLUDED.signature_url,
          document_title = EXCLUDED.document_title,
          invoice_prefix = EXCLUDED.invoice_prefix,
          signatory_label = EXCLUDED.signatory_label,
          bank_details = EXCLUDED.bank_details,
          terms_and_conditions = EXCLUDED.terms_and_conditions,
          footer_note = EXCLUDED.footer_note,
          show_mrp_savings = EXCLUDED.show_mrp_savings,
          show_doctor = EXCLUDED.show_doctor,
          show_hsn = EXCLUDED.show_hsn,
          print_bank_details = EXCLUDED.print_bank_details,
          updated_at = EXCLUDED.updated_at
        """,
        settings.pharmacyId(),
        settings.template().name(),
        settings.accentColor(),
        settings.logoUrl(),
        settings.signatureUrl(),
        settings.documentTitle(),
        settings.invoicePrefix(),
        settings.signatoryLabel(),
        writeJson(settings.bankDetails()),
        settings.termsAndConditions(),
        settings.footerNote(),
        settings.showMrpSavings(),
        settings.showDoctor(),
        settings.showHsn(),
        settings.printBankDetails(),
        Timestamp.from(settings.updatedAt()));
    return settings;
  }

  private InvoiceSettings mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new InvoiceSettings(
        (UUID) rs.getObject("pharmacy_id"),
        InvoiceTemplate.valueOf(rs.getString("template")),
        rs.getString("accent_color"),
        rs.getString("logo_url"),
        rs.getString("signature_url"),
        rs.getString("document_title"),
        rs.getString("invoice_prefix"),
        rs.getString("signatory_label"),
        readJson(rs.getString("bank_details")),
        rs.getString("terms_and_conditions"),
        rs.getString("footer_note"),
        rs.getBoolean("show_mrp_savings"),
        rs.getBoolean("show_doctor"),
        rs.getBoolean("show_hsn"),
        rs.getBoolean("print_bank_details"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static InvoiceSettings defaults(UUID pharmacyId, Instant now) {
    return new InvoiceSettings(
        pharmacyId,
        InvoiceTemplate.MODERN,
        "#2563EB",
        null,
        null,
        "Tax Invoice",
        "INV",
        "Authorized Signatory",
        null,
        null,
        null,
        true,
        true,
        true,
        false,
        now);
  }

  private String writeJson(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize bank_details", e);
    }
  }

  private Map<String, Object> readJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      return Map.of();
    }
  }
}
