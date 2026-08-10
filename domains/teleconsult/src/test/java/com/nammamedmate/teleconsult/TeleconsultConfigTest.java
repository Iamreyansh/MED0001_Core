package com.nammamedmate.teleconsult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.security.AesGcmCipher;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class TeleconsultConfigTest {

  @Test
  void localCipherEncryptsWithBlankKeyFallback() {
    TeleconsultConfig config = new TeleconsultConfig();
    AesGcmCipher cipher = config.localTeleconsultPhoneCipher("");
    String ct = cipher.encrypt("+91-9000000000");
    assertThat(cipher.decrypt(ct)).isEqualTo("+91-9000000000");
  }

  @Test
  void localCipherAcceptsHexAndBase64Keys() {
    TeleconsultConfig config = new TeleconsultConfig();
    String hex = "00".repeat(32);
    AesGcmCipher hexCipher = config.localTeleconsultPhoneCipher(hex);
    assertThat(hexCipher.decrypt(hexCipher.encrypt("x"))).isEqualTo("x");

    AesGcmCipher b64 =
        config.localTeleconsultPhoneCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    assertThat(b64.decrypt(b64.encrypt("y"))).isEqualTo("y");
  }

  @Test
  void deployedCipherRejectsBlankAndLocalZeroKey() {
    TeleconsultConfig config = new TeleconsultConfig();
    assertThatThrownBy(() -> config.deployedTeleconsultPhoneCipher("", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Secrets Manager");
    assertThatThrownBy(
            () ->
                config.deployedTeleconsultPhoneCipher(
                    "", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void deployedCipherUsesTeleconsultKeyOrMfaFallback() {
    TeleconsultConfig config = new TeleconsultConfig();
    String strong = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";
    AesGcmCipher fromDedicated = config.deployedTeleconsultPhoneCipher(strong, "ignored");
    assertThat(fromDedicated.decrypt(fromDedicated.encrypt("a"))).isEqualTo("a");

    AesGcmCipher fromMfa = config.deployedTeleconsultPhoneCipher("", strong);
    assertThat(fromMfa.decrypt(fromMfa.encrypt("b"))).isEqualTo("b");
  }

  @Test
  void buildCipherHexBranch() {
    AesGcmCipher c = TeleconsultConfig.buildCipher("11".repeat(32));
    assertThat(c.decrypt(c.encrypt("z"))).isEqualTo("z");
  }

  @Test
  void clockDefaultsToUtc() {
    Clock clock = new TeleconsultConfig().teleconsultClock();
    assertThat(clock).isNotNull();
  }

  @Test
  void stubsForCartAndNotifications() {
    TeleconsultConfig config = new TeleconsultConfig();
    assertThat(
            config
                .teleconsultStubCartPort()
                .isActiveCartOwnedBy(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
        .isTrue();
    assertThat(
            config.teleconsultStubCartPort().isActiveCartOwnedBy(null, java.util.UUID.randomUUID()))
        .isFalse();
    config
        .teleconsultStubNotificationDispatchPort()
        .notifyConsultAutoCancelled(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
    config
        .teleconsultStubNotificationDispatchPort()
        .notifyConsultStatusUpdated(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "IN_CALL");

    Clock clock = Clock.systemUTC();
    var issued =
        config
            .teleconsultStubEPrescriptionWritePort(clock)
            .create(
                new com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort
                    .CreateRequest(
                    null,
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    "Dr",
                    "MBBS",
                    "R1",
                    "GP",
                    "Pat",
                    java.util.List.of(),
                    true,
                    "advice",
                    null,
                    null));
    assertThat(issued.rxId()).startsWith("RX-");
    assertThatThrownBy(
            () ->
                config
                    .teleconsultStubCartLinkPort()
                    .attachPrescription(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID()))
        .hasMessageContaining("Cart");
  }
}
