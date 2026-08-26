package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FailClosedGspClientTest {

  @Test
  void generateIrnFailsClosed() {
    FailClosedGspClient client = new FailClosedGspClient();
    assertThatThrownBy(() -> client.generateIrn(Map.of()))
        .hasMessageContaining("GSP provider is not configured");
    assertThatThrownBy(() -> client.cancelIrn("irn", "1", "dup"))
        .hasMessageContaining("GSP provider is not configured");
    assertThatThrownBy(() -> client.getStatus("irn"))
        .hasMessageContaining("GSP provider is not configured");
    assertThatThrownBy(client::refreshToken).hasMessageContaining("GSP provider is not configured");
    assertThat(client.currentToken()).isEmpty();
  }
}
