package com.nammamedmate.inventory.adapter.out.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Minimal single-page PDF writer for expiry reports (no PDF library). */
public final class SimplePdfExporter {

  private SimplePdfExporter() {}

  public static byte[] export(String title, List<Map<String, Object>> rows) {
    StringBuilder content = new StringBuilder();
    content.append(title == null ? "Expiry Report" : title).append('\n');
    if (rows != null) {
      for (Map<String, Object> row : rows) {
        content
            .append(str(row.get("product_name")))
            .append(" | ")
            .append(str(row.get("batch_number")))
            .append(" | exp=")
            .append(str(row.get("expiry_date")))
            .append(" | qty=")
            .append(str(row.get("quantity_current")))
            .append(" | var=")
            .append(str(row.get("value_at_risk")))
            .append('\n');
      }
    }
    return buildPdf(content.toString());
  }

  public static byte[] buildPdf(String text) {
    String escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    // PDF text objects treat \n poorly; use spaces for line breaks in stream.
    String streamText = escaped.replace("\n", " ");
    String stream = "BT /F1 10 Tf 40 750 Td (" + streamText + ") Tj ET";
    byte[] streamBytes = stream.getBytes(StandardCharsets.US_ASCII);

    StringBuilder pdf = new StringBuilder();
    pdf.append("%PDF-1.4\n");
    int[] offsets = new int[5];
    offsets[1] = pdf.length();
    pdf.append("1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
    offsets[2] = pdf.length();
    pdf.append("2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
    offsets[3] = pdf.length();
    pdf.append(
        "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
            + " /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>endobj\n");
    offsets[4] = pdf.length();
    pdf.append("4 0 obj<< /Length ")
        .append(streamBytes.length)
        .append(" >>stream\n")
        .append(stream)
        .append("\nendstream\nendobj\n");
    int fontOffset = pdf.length();
    pdf.append("5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n");
    int xref = pdf.length();
    pdf.append("xref\n0 6\n");
    pdf.append("0000000000 65535 f \n");
    pdf.append(pad10(offsets[1])).append(" 00000 n \n");
    pdf.append(pad10(offsets[2])).append(" 00000 n \n");
    pdf.append(pad10(offsets[3])).append(" 00000 n \n");
    pdf.append(pad10(offsets[4])).append(" 00000 n \n");
    pdf.append(pad10(fontOffset)).append(" 00000 n \n");
    pdf.append("trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private static String pad10(int offset) {
    String s = Integer.toString(Math.max(0, offset));
    return "0".repeat(Math.max(0, 10 - s.length())) + s;
  }

  private static String str(Object v) {
    return v == null ? "" : v.toString();
  }
}
