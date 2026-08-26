package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookHmacTest {

  @Test
  void hmacSha256HexCoversNullBodyAndExceptionPath() {
    assertThat(WebhookHmac.hmacSha256Hex("secret", null)).isNotBlank();
    assertThat(WebhookHmac.hmacSha256Hex("secret", "body".getBytes(StandardCharsets.UTF_8)))
        .isNotBlank();
    assertThatThrownBy(() -> WebhookHmac.hmacSha256Hex(null, new byte[0]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HMAC-SHA256 failed");
  }
}
