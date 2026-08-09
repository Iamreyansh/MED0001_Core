package com.nammamedmate.crm.adapter.out.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal multi-line PDF writer (no PDF library). */
public final class SimplePdfExporter {

  public static final int A4_WIDTH = 612;
  public static final int A4_HEIGHT = 792;

  private SimplePdfExporter() {}

  public static byte[] export(String title, List<String> lines) {
    List<String> content = new ArrayList<>();
    if (title != null && !title.isBlank()) {
      content.add(title);
    }
    if (lines != null) {
      content.addAll(lines);
    }
    return buildPdf(content, A4_WIDTH, A4_HEIGHT, 10, 40, 750);
  }

  static byte[] buildPdf(
      List<String> lines, int width, int height, int fontSize, int marginX, int startY) {
    StringBuilder stream = new StringBuilder("BT /F1 ");
    stream.append(fontSize).append(" Tf ").append(marginX).append(' ').append(startY).append(" Td");
    boolean first = true;
    for (String raw : lines == null ? List.<String>of() : lines) {
      String escaped =
          (raw == null ? "" : raw)
              .replace("\\", "\\\\")
              .replace("(", "\\(")
              .replace(")", "\\)")
              .replace("\r", " ")
              .replace("\n", " ");
      if (!first) {
        stream.append(" 0 -").append(fontSize + 2).append(" Td");
      }
      stream.append(" (").append(escaped).append(") Tj");
      first = false;
    }
    if (first) {
      stream.append(" () Tj");
    }
    stream.append(" ET");
    String streamBody = stream.toString();
    byte[] streamBytes = streamBody.getBytes(StandardCharsets.US_ASCII);

    StringBuilder pdf = new StringBuilder();
    pdf.append("%PDF-1.4\n");
    int[] offsets = new int[5];
    offsets[1] = pdf.length();
    pdf.append("1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
    offsets[2] = pdf.length();
    pdf.append("2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
    offsets[3] = pdf.length();
    pdf.append("3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ")
        .append(width)
        .append(' ')
        .append(height)
        .append("] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>endobj\n");
    offsets[4] = pdf.length();
    pdf.append("4 0 obj<< /Length ")
        .append(streamBytes.length)
        .append(" >>stream\n")
        .append(streamBody)
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
}
