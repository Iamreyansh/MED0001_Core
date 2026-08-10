package com.nammamedmate.teleconsult;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.teleconsult.application.port.out.CartLinkPort;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.Issued;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class TeleconsultConfig {

  private static final String LOCAL_ONLY_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock teleconsultClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(CartPort.class)
  CartPort teleconsultStubCartPort() {
    return (cartId, customerId) -> cartId != null && customerId != null;
  }

  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort teleconsultStubNotificationDispatchPort() {
    return new NotificationDispatchPort() {
      @Override
      public void notifyConsultAutoCancelled(java.util.UUID customerId, java.util.UUID consultId) {}

      @Override
      public void notifyConsultStatusUpdated(
          java.util.UUID customerId, java.util.UUID consultId, String status) {}
    };
  }

  @Bean
  @ConditionalOnMissingBean(EPrescriptionWritePort.class)
  EPrescriptionWritePort teleconsultStubEPrescriptionWritePort(Clock clock) {
    return request -> {
      var issuedAt = request.issuedAt() == null ? clock.instant() : request.issuedAt();
      return new Issued(
          request.id() == null ? Ids.newId() : request.id(),
          "RX-STUB-NMM-000001",
          "stub-hash",
          issuedAt.plus(Duration.ofDays(90)),
          issuedAt,
          request.medicines() == null ? List.of() : request.medicines());
    };
  }

  @Bean
  @ConditionalOnMissingBean(CartLinkPort.class)
  CartLinkPort teleconsultStubCartLinkPort() {
    return (customerId, cartId, prescriptionId) -> {
      throw new AppException("CART_NOT_FOUND", "Cart not found or not ACTIVE", 404);
    };
  }

  /** Local/dev/IT: blank or known local key allowed for internal_phone encryption. */
  @Bean(name = "teleconsultPhoneCipher")
  @Qualifier("teleconsultPhoneCipher")
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(name = "teleconsultPhoneCipher")
  AesGcmCipher localTeleconsultPhoneCipher(
      @Value("${medmate.teleconsult.encryption-key-base64:}") String key) {
    return buildCipher(key == null || key.isBlank() ? LOCAL_ONLY_KEY : key.trim());
  }

  /**
   * Staging/prod: dedicated teleconsult key or MFA fallback — never blank / known local zero key.
   */
  @Bean(name = "teleconsultPhoneCipher")
  @Qualifier("teleconsultPhoneCipher")
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(name = "teleconsultPhoneCipher")
  AesGcmCipher deployedTeleconsultPhoneCipher(
      @Value("${medmate.teleconsult.encryption-key-base64:}") String teleconsultKey,
      @Value("${medmate.mfa.encryption-key-base64}") String mfaKey) {
    String key =
        teleconsultKey != null && !teleconsultKey.isBlank() ? teleconsultKey.trim() : mfaKey;
    if (key == null || key.isBlank() || LOCAL_ONLY_KEY.equals(key.trim())) {
      throw new IllegalStateException(
          "medmate.teleconsult.encryption-key-base64 (or MFA fallback) must be injected via Secrets Manager");
    }
    return buildCipher(key.trim());
  }

  static AesGcmCipher buildCipher(String trimmed) {
    if (trimmed.length() == 64 && trimmed.matches("[0-9a-fA-F]+")) {
      return new AesGcmCipher(HexFormat.of().parseHex(trimmed));
    }
    return AesGcmCipher.fromBase64Key(trimmed);
  }
}
