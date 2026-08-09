package com.nammamedmate.inventory.adapter.out.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleXlsxExporterTest {

  @Test
  void export_producesZipMagicAndEscapes() {
    SimpleXlsxExporter exporter = new SimpleXlsxExporter();
    byte[] bytes =
        exporter.export(
            List.of(
                Map.of(
                    "id", "x",
                    "name", "A & B <C> \"D\"",
                    "flags", Arrays.asList("LOW_STOCK", null, "dead_stock"))));

    assertThat(SimpleXlsxExporter.looksLikeXlsx(bytes)).isTrue();
    assertThat(bytes.length).isGreaterThan(100);
    assertThat(SimpleXlsxExporter.xmlEscape(null)).isEmpty();
    assertThat(SimpleXlsxExporter.xmlEscape("")).isEmpty();
    assertThat(SimpleXlsxExporter.xmlEscape("a\u0001b")).isEqualTo("ab");
    assertThat(SimpleXlsxExporter.xmlEscape("a\tb\nc\rd")).isEqualTo("a\tb\nc\rd");
    assertThat(SimpleXlsxExporter.looksLikeXlsx(null)).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {1})).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {'P', 'X', 0, 0})).isFalse();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new byte[] {'X', 'K', 0, 0})).isFalse();
  }

  @Test
  void export_nullProducts_headerOnly() {
    assertThat(SimpleXlsxExporter.looksLikeXlsx(new SimpleXlsxExporter().export(null))).isTrue();
  }

  @Test
  void exportSheet_customHeaders() {
    byte[] bytes =
        new SimpleXlsxExporter()
            .exportSheet(
                "Expiry",
                new String[] {"product_name", "batch_number"},
                List.of(Map.of("product_name", "Amox", "batch_number", "AM1")));
    assertThat(SimpleXlsxExporter.looksLikeXlsx(bytes)).isTrue();
    assertThat(
            SimpleXlsxExporter.looksLikeXlsx(
                new SimpleXlsxExporter().exportSheet(null, new String[] {"a"}, List.of())))
        .isTrue();
  }

  @Test
  void writeXlsx_ioFailure() {
    OutputStream boom =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("boom");
          }
        };
    assertThatThrownBy(
            () ->
                SimpleXlsxExporter.writeXlsx(
                    boom, "Inventory", new String[] {"id", "name"}, List.of()))
        .isInstanceOf(UncheckedIOException.class);
  }
}
