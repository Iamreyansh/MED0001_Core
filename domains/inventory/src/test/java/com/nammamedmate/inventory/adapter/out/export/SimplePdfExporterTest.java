package com.nammamedmate.inventory.adapter.out.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimplePdfExporterTest {

  @Test
  void buildsMinimalPdf() {
    byte[] pdf =
        SimplePdfExporter.export(
            "Expiry Report",
            List.of(
                Map.of(
                    "product_name",
                    "Amox (test)",
                    "batch_number",
                    "AM1",
                    "expiry_date",
                    "2026-08-15",
                    "quantity_current",
                    30,
                    "value_at_risk",
                    "255.00")));
    assertThat(new String(pdf)).startsWith("%PDF-1.4");
    assertThat(new String(pdf)).contains("%%EOF");
    assertThat(SimplePdfExporter.export(null, null)[0]).isEqualTo((byte) '%');
    assertThat(
            SimplePdfExporter.export(
                "t", List.of(Map.of("product_name", "x", "batch_number", "y"))))
        .isNotEmpty();
    // force null field path in str()
    java.util.HashMap<String, Object> sparse = new java.util.HashMap<>();
    sparse.put("product_name", null);
    sparse.put("batch_number", null);
    sparse.put("expiry_date", null);
    sparse.put("quantity_current", null);
    sparse.put("value_at_risk", null);
    assertThat(SimplePdfExporter.export("t", List.of(sparse))[0]).isEqualTo((byte) '%');
  }
}
