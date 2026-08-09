package com.nammamedmate.pos.adapter.out.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimplePdfExporterTest {

  @Test
  void exportA4AndThermalAndEscapes() {
    List<String> withNull = new ArrayList<>();
    withNull.add("line (1)");
    withNull.add("line\\2");
    withNull.add(null);
    byte[] a4 = SimplePdfExporter.export("Title", withNull, false);
    assertThat(a4[0]).isEqualTo((byte) '%');
    assertThat(new String(a4)).contains("/MediaBox [0 0 612 792]");

    byte[] thermal = SimplePdfExporter.export(" ", null, true);
    assertThat(thermal[0]).isEqualTo((byte) '%');
    assertThat(new String(thermal)).contains("/MediaBox [0 0 226 800]");

    assertThat(SimplePdfExporter.buildPdf(null)[0]).isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.buildPdf("a\nb")[0]).isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.buildPdf(List.of(), 612, 792, 10, 40, 750)[0])
        .isEqualTo((byte) '%');
    assertThat(SimplePdfExporter.buildPdf(null, 612, 792, 10, 40, 750)[0]).isEqualTo((byte) '%');
  }
}
