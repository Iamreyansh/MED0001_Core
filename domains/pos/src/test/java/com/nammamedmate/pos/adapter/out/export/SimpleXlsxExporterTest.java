package com.nammamedmate.pos.adapter.out.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleXlsxExporterTest {

  @Test
  void exportAndEscapeBranches() {
    SimpleXlsxExporter exporter = new SimpleXlsxExporter();
    Map<String, Object> row = new HashMap<>();
    row.put("a", "1&<>\"");
    row.put("b", null);
    byte[] bytes = exporter.exportSheet("Invoices", new String[] {"a", "b"}, List.of(row));
    assertThat(SimpleXlsxExporter.looksLikeXlsx(bytes)).isTrue();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(null)).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {1})).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {'P', 'X', 0, 0})).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {'P', 'K'})).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {'X', 'K', 0, 0})).isFalse();
    assertThat(SimpleXlsxExporter.xmlEscape(null)).isEmpty();
    assertThat(SimpleXlsxExporter.xmlEscape("")).isEmpty();
    assertThat(SimpleXlsxExporter.xmlEscape("a\tb\nc\rd")).isEqualTo("a\tb\nc\rd");
    assertThat(SimpleXlsxExporter.xmlEscape("a\u0001b")).isEqualTo("ab");
    assertThat(
            SimpleXlsxExporter.looksLikeXlsx(exporter.exportSheet(null, new String[] {"a"}, null)))
        .isTrue();
    // wide header set exercises colName for index >= 1
    assertThat(
            SimpleXlsxExporter.looksLikeXlsx(
                exporter.exportSheet(
                    "S",
                    new String[] {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"},
                    List.of())))
        .isTrue();
  }

  @Test
  void writeXlsxIoFailure() {
    OutputStream boom =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("boom");
          }
        };
    assertThatThrownBy(
            () -> SimpleXlsxExporter.writeXlsx(boom, "Invoices", new String[] {"a"}, List.of()))
        .isInstanceOf(UncheckedIOException.class);
  }
}
