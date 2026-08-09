package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubCommunicationProviderTest {

  private StubCommunicationProvider stub;

  @BeforeEach
  void setUp() {
    stub = new StubCommunicationProvider();
  }

  @Test
  void healthPingAndMarkDown() {
    assertThat(stub.healthPing(null)).isFalse();
    assertThat(stub.healthPing("MSG91")).isTrue();
    stub.markDown(null);
    stub.markDown("msg91");
    assertThat(stub.healthPing("MSG91")).isFalse();
    stub.markHealthy(null);
    stub.markHealthy("MSG91");
    assertThat(stub.healthPing("MSG91")).isTrue();
    stub.markDown("TWILIO");
    stub.clear();
    assertThat(stub.healthPing("TWILIO")).isTrue();
  }

  @Test
  void connectivityTestBranches() {
    assertThat(stub.connectivityTest(null, Map.of("api_key", "ok"))).isFalse();
    assertThat(stub.connectivityTest("MSG91", null)).isFalse();
    assertThat(stub.connectivityTest("MSG91", Map.of())).isFalse();
    assertThat(stub.connectivityTest("MSG91", Map.of("api_key", "  "))).isFalse();
    assertThat(stub.connectivityTest("MSG91", Map.of("api_key", "fail-me"))).isFalse();
    assertThat(stub.connectivityTest("MSG91", Map.of("api_key", "invalid-key"))).isFalse();
    assertThat(stub.connectivityTest("MSG91", Map.of("api_key", "good-key"))).isTrue();
    stub.markDown("MSG91");
    assertThat(stub.connectivityTest("MSG91", Map.of("api_key", "good-key"))).isFalse();
  }

  @Test
  void sendTestBranches() {
    assertThatThrownBy(() -> stub.sendTest(null, "SMS", "+91", "OTP", true))
        .isInstanceOf(AppException.class);
    stub.markDown("MSG91");
    assertThatThrownBy(() -> stub.sendTest("MSG91", "SMS", "+91", "OTP", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");
    stub.markHealthy("MSG91");
    assertThatThrownBy(() -> stub.sendTest("MSG91", "SMS", "+91", "OTP", false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");
    assertThat(stub.sendTest("MSG91", "SMS", "+91", "OTP", true).status()).isEqualTo("SENT");
  }
}
