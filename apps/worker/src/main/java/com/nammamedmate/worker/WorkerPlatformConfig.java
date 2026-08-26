package com.nammamedmate.worker;

import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Shared platform beans the worker needs because it component-scans domain application services
 * that take {@link RateLimiter} / {@link AesGcmCipher} — same contract as API {@code
 * PlatformConfig}.
 */
@Configuration
public class WorkerPlatformConfig {

  private static final String LOCAL_ONLY_MFA_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  RateLimiter rateLimiter(Clock clock) {
    return new InMemoryRateLimiter(clock);
  }

  @Bean
  @Primary
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(AesGcmCipher.class)
  AesGcmCipher localAesGcmCipher(
      @Value("${medmate.mfa.encryption-key-base64:" + LOCAL_ONLY_MFA_KEY + "}") String keyBase64) {
    return AesGcmCipher.fromBase64Key(keyBase64);
  }

  @Bean
  @Primary
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(AesGcmCipher.class)
  AesGcmCipher deployedAesGcmCipher(
      @Value("${medmate.mfa.encryption-key-base64}") String keyBase64) {
    if (keyBase64 == null || keyBase64.isBlank() || LOCAL_ONLY_MFA_KEY.equals(keyBase64.trim())) {
      throw new IllegalStateException(
          "medmate.mfa.encryption-key-base64 must be injected via Secrets Manager");
    }
    return AesGcmCipher.fromBase64Key(keyBase64);
  }

  @Bean
  @Qualifier("paymentMethodCipher")
  @Profile("!prod & !staging")
  AesGcmCipher localPaymentMethodCipher(
      @Value("${medmate.payment.encryption-key-base64:}") String paymentKey,
      @Value("${medmate.mfa.encryption-key-base64:" + LOCAL_ONLY_MFA_KEY + "}") String mfaKey) {
    String key = paymentKey == null || paymentKey.isBlank() ? mfaKey : paymentKey;
    return AesGcmCipher.fromBase64Key(key);
  }

  @Bean
  @Qualifier("paymentMethodCipher")
  @Profile({"prod", "staging"})
  AesGcmCipher deployedPaymentMethodCipher(
      @Value("${medmate.payment.encryption-key-base64:}") String paymentKey,
      @Value("${medmate.mfa.encryption-key-base64}") String mfaKey) {
    String key = paymentKey == null || paymentKey.isBlank() ? mfaKey : paymentKey;
    if (key == null || key.isBlank() || LOCAL_ONLY_MFA_KEY.equals(key.trim())) {
      throw new IllegalStateException(
          "medmate.payment.encryption-key-base64 (or MFA fallback) must be injected via Secrets Manager");
    }
    return AesGcmCipher.fromBase64Key(key);
  }

  @Bean
  @Qualifier("bankAccountCipher")
  @Profile("!prod & !staging")
  AesGcmCipher localBankAccountCipher(
      @Value("${medmate.crypto.bank-account-key-base64:}") String bankKey,
      @Value("${medmate.payment.encryption-key-base64:}") String paymentKey,
      @Value("${medmate.mfa.encryption-key-base64:" + LOCAL_ONLY_MFA_KEY + "}") String mfaKey) {
    String key =
        bankKey != null && !bankKey.isBlank()
            ? bankKey
            : (paymentKey != null && !paymentKey.isBlank() ? paymentKey : mfaKey);
    return AesGcmCipher.fromBase64Key(key);
  }

  @Bean
  @Qualifier("bankAccountCipher")
  @Profile({"prod", "staging"})
  AesGcmCipher deployedBankAccountCipher(
      @Value("${medmate.crypto.bank-account-key-base64:}") String bankKey,
      @Value("${medmate.payment.encryption-key-base64:}") String paymentKey,
      @Value("${medmate.mfa.encryption-key-base64}") String mfaKey) {
    String key =
        bankKey != null && !bankKey.isBlank()
            ? bankKey
            : (paymentKey != null && !paymentKey.isBlank() ? paymentKey : mfaKey);
    if (key == null || key.isBlank() || LOCAL_ONLY_MFA_KEY.equals(key.trim())) {
      throw new IllegalStateException(
          "medmate.crypto.bank-account-key-base64 (or payment/MFA fallback) must be injected via Secrets Manager");
    }
    return AesGcmCipher.fromBase64Key(key);
  }
}
