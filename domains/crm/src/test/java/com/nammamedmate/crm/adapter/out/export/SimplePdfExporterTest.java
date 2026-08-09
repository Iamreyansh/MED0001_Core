package com.nammamedmate.crm.adapter.out.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SimplePdfExporterTest {

  @Test
  void buildsMinimalPdf() {
    assertThat(SimplePdfExporter.export("Title", List.of("a", "b(c)", "d\\e"))[0])
        .isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.export(null, null)[0]).isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.export(" ", List.of())[0]).isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.buildPdf(null, 612, 792, 10, 40, 750)[0]).isEqualTo((byte) '%');
    assertThat(
            SimplePdfExporter.buildPdf(
                java.util.Arrays.asList((String) null), 612, 792, 10, 40, 750)[0])
        .isEqualTo((byte) '%');
  }
}
