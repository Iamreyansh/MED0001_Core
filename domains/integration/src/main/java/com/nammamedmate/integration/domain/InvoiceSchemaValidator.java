package com.nammamedmate.integration.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local GST e-invoice SCHEMA 1.1 gate before GSP/NIC call (BR-9 / AC-001).
 *
 * <p>ponytail: subset of SCHEMA 1.1 required fields only; full NIC XSD is the upgrade path.
 */
public final class InvoiceSchemaValidator {

  private InvoiceSchemaValidator() {}

  public static void validate(Map<String, Object> invoiceData) {
    if (invoiceData == null || invoiceData.isEmpty()) {
      throw schema("invoice_data", "invoice_data is required");
    }
    requireGstin(invoiceData, "seller_gstin");
    requireGstin(invoiceData, "buyer_gstin");
    requireNonBlank(invoiceData, "invoice_number");
    requireDate(invoiceData, "invoice_date");
    String supply = str(invoiceData.get("supply_type"));
    if (supply.isBlank()) {
      throw schema("supply_type", "supply_type is required");
    }
    if (!"B2B".equalsIgnoreCase(supply)) {
      throw schema("supply_type", "supply_type must be B2B for IRN generation");
    }
    requireNonBlank(invoiceData, "invoice_type");
    Object itemsObj = invoiceData.get("items");
    if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
      throw schema("items", "items must be a non-empty array");
    }
    for (int i = 0; i < items.size(); i++) {
      Object raw = items.get(i);
      if (!(raw instanceof Map<?, ?> item)) {
        throw schema("items[" + i + "]", "item must be an object");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) item;
      requireNonBlank(map, "product_name", "items[" + i + "].product_name");
      requireNonBlank(map, "hsn_code", "items[" + i + "].hsn_code");
      requireNumber(map, "qty", "items[" + i + "].qty");
      requireNumber(map, "unit_price", "items[" + i + "].unit_price");
      requireNumber(map, "assbl_value", "items[" + i + "].assbl_value");
      requireNumber(map, "gst_rate", "items[" + i + "].gst_rate");
      requireNumber(map, "total", "items[" + i + "].total");
    }
    Object taxObj = invoiceData.get("tax_amounts");
    if (!(taxObj instanceof Map<?, ?> tax)) {
      throw schema("tax_amounts", "tax_amounts is required");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> taxMap = (Map<String, Object>) tax;
    requireNumber(taxMap, "taxable_value", "tax_amounts.taxable_value");
    requireNumber(taxMap, "total_invoice_value", "tax_amounts.total_invoice_value");
  }

  private static void requireGstin(Map<String, Object> data, String field) {
    String value = str(data.get(field)).toUpperCase(Locale.ROOT);
    if (value.isBlank()) {
      throw schema(field, field + " is required");
    }
    if (!GstinChecksum.isValid(value)) {
      throw schema(field, field + " failed GSTIN checksum validation");
    }
  }

  private static void requireNonBlank(Map<String, Object> data, String field) {
    requireNonBlank(data, field, field);
  }

  private static void requireNonBlank(Map<String, Object> data, String field, String path) {
    if (str(data.get(field)).isBlank()) {
      throw schema(path, path + " is required");
    }
  }

  private static void requireDate(Map<String, Object> data, String field) {
    String raw = str(data.get(field));
    if (raw.isBlank()) {
      throw schema(field, field + " is required");
    }
    try {
      LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw);
    } catch (DateTimeParseException e) {
      throw schema(field, field + " must be YYYY-MM-DD");
    }
  }

  private static void requireNumber(Map<String, Object> data, String field, String path) {
    Object v = data.get(field);
    if (v == null) {
      throw schema(path, path + " is required");
    }
    if (v instanceof Number) {
      return;
    }
    try {
      new BigDecimal(v.toString());
    } catch (NumberFormatException e) {
      throw schema(path, path + " must be a number");
    }
  }

  private static AppException schema(String field, String message) {
    return new AppException("INVALID_INVOICE_SCHEMA", message, 422, null, Map.of("field", field));
  }

  private static String str(Object v) {
    return v == null ? "" : v.toString().trim();
  }
}
