package com.nammamedmate.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.adapter.out.ratelimit.RedisRateLimiter;
import com.nammamedmate.auth.adapter.out.revocation.RedisTokenRevocationStore;
import com.nammamedmate.automation.adapter.in.messaging.AutomationTriggerConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.S3PresignedUrlService;
import com.nammamedmate.messaging.ConsumerInbox;
import com.nammamedmate.messaging.JdbcConsumerInbox;
import com.nammamedmate.messaging.JdbcOutboxStore;
import com.nammamedmate.messaging.JdbcProviderOperationStore;
import com.nammamedmate.messaging.JdbcWebhookInbox;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.OutboxStore;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.messaging.SchedulerLease;
import com.nammamedmate.messaging.WebhookInbox;
import com.nammamedmate.messaging.SqsEventDispatcher;
import com.nammamedmate.messaging.SqsOutboxTransport;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.RsaKeyLoader;
import com.nammamedmate.security.TokenRevocationStore;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class PlatformConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  private static final String LOCAL_ONLY_MFA_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  /** Local/dev/IT: fixed non-production key is allowed. */
  @Bean
  @Primary
  @Profile("!prod & !staging")
  AesGcmCipher localAesGcmCipher(
      @Value("${medmate.mfa.encryption-key-base64:" + LOCAL_ONLY_MFA_KEY + "}") String keyBase64) {
    return AesGcmCipher.fromBase64Key(keyBase64);
  }

  /** Staging/prod: key must come from Secrets Manager — never the known local default. */
  @Bean
  @Primary
  @Profile({"prod", "staging"})
  AesGcmCipher deployedAesGcmCipher(
      @Value("${medmate.mfa.encryption-key-base64}") String keyBase64) {
    if (keyBase64 == null || keyBase64.isBlank() || LOCAL_ONLY_MFA_KEY.equals(keyBase64.trim())) {
      throw new IllegalStateException(
          "medmate.mfa.encryption-key-base64 must be injected via Secrets Manager");
    }
    return AesGcmCipher.fromBase64Key(keyBase64);
  }

  /**
   * Dedicated cipher for saved payment methods (UPI / Razorpay token). Falls back to MFA key when
   * payment key is unset (local/legacy secrets).
   */
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

  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  RateLimiter rateLimiter(Clock clock, ObjectProvider<StringRedisTemplate> redis) {
    StringRedisTemplate template = redis.getIfAvailable();
    if (template != null) {
      return new RedisRateLimiter(template);
    }
    return new InMemoryRateLimiter(clock);
  }

  @Bean
  TokenRevocationStore tokenRevocationStore(
      Clock clock, ObjectProvider<StringRedisTemplate> redis) {
    StringRedisTemplate template = redis.getIfAvailable();
    if (template != null) {
      return new RedisTokenRevocationStore(template);
    }
    return new InMemoryTokenRevocationStore(clock);
  }

  @Bean
  OutboxStore outboxStore(JdbcTemplate jdbcTemplate) {
    return new JdbcOutboxStore(jdbcTemplate);
  }

  @Bean
  OutboxPublisher outboxPublisher(OutboxStore store, ObjectMapper objectMapper) {
    return new OutboxPublisher(store, objectMapper);
  }

  @Bean
  SchedulerLease schedulerLease(JdbcTemplate jdbcTemplate, Clock clock) {
    return new SchedulerLease(jdbcTemplate, clock);
  }

  @Bean
  ConsumerInbox consumerInbox(JdbcTemplate jdbcTemplate) {
    return new JdbcConsumerInbox(jdbcTemplate);
  }

  @Bean
  ProviderOperationStore providerOperationStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProviderOperationStore(jdbcTemplate);
  }

  @Bean
  WebhookInbox webhookInbox(JdbcTemplate jdbcTemplate) {
    return new JdbcWebhookInbox(jdbcTemplate);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(SqsClient.class)
  SqsClient sqsClient() {
    return SqsClient.create();
  }

  @Bean
  SqsEventDispatcher sqsEventDispatcher(
      OutboxStore store,
      ObjectProvider<SqsClient> sqs,
      @Value("${medmate.sqs.queue-url:}") String queueUrl,
      AutoKycOutboxConsumer autoKycOutboxConsumer,
      OrderDeliveredReferralConsumer orderDeliveredReferralConsumer,
      OrderDeliveredLoyaltyConsumer orderDeliveredLoyaltyConsumer,
      AutomationTriggerConsumer automationTriggerConsumer) {
    String url = queueUrl == null ? "" : queueUrl.trim();
    return new SqsEventDispatcher(
        store,
        message -> {
          if (!url.isEmpty()) {
            new SqsOutboxTransport(sqs.getObject(), url).accept(message);
            return;
          }
          autoKycOutboxConsumer.accept(message);
          orderDeliveredReferralConsumer.accept(message);
          orderDeliveredLoyaltyConsumer.accept(message);
          automationTriggerConsumer.accept(message);
        },
        25);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner sqsQueueUrlGuard(
      @Value("${medmate.sqs.queue-url:}") String queueUrl) {
    return args -> {
      if (queueUrl == null || queueUrl.isBlank()) {
        throw new IllegalStateException(
            "medmate.sqs.queue-url must be injected in deployed profiles");
      }
    };
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner internalServiceTokenGuard(
      @Value("${medmate.internal.service-token:}") String token) {
    return args -> {
      if (token == null || token.isBlank() || "local-internal-wallet-token".equals(token.trim())) {
        throw new IllegalStateException(
            "medmate.internal.service-token must be injected in deployed profiles");
      }
    };
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner notificationWebhookSecretGuard(
      @Value("${medmate.sms.webhook-secret:}") String smsSecret,
      @Value("${medmate.email.webhook-secret:}") String emailSecret) {
    return args ->
        com.nammamedmate.notification.application.NotificationWebhookAuth
            .validateSecretsForDeployedProfile(smsSecret, emailSecret);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner kycWebhookSecretGuard(
      @Value("${medmate.kyc.webhook-secret:}") String webhookSecret) {
    return args ->
        com.nammamedmate.pharmacy.application.AutoKycService
            .validateWebhookSecretForDeployedProfile(webhookSecret, true);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner razorpayxWebhookSecretGuard(
      @Value("${medmate.razorpayx.webhook-secret:}") String webhookSecret) {
    return args ->
        com.nammamedmate.pharmacy.application.AdminPharmacySettlementService
            .validateWebhookSecretForDeployedProfile(webhookSecret, true);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner razorpayPaymentSecretsGuard(
      @Value("${medmate.razorpay.key-id:}") String keyId,
      @Value("${medmate.razorpay.key-secret:}") String keySecret,
      @Value("${medmate.razorpay.webhook-secret:}") String webhookSecret) {
    return args ->
        com.nammamedmate.payment.PaymentConfig.validateRazorpaySecretsForDeployedProfile(
            keyId, keySecret, webhookSecret, true);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner razorpayXSecretsGuard(
      @Value("${medmate.razorpayx.key-id:}") String keyId,
      @Value("${medmate.razorpayx.key-secret:}") String keySecret) {
    return args ->
        com.nammamedmate.payment.PaymentConfig.validateRazorpayXSecretsForDeployedProfile(
            keyId, keySecret, true);
  }

  @Bean
  @Profile({"prod", "staging"})
  org.springframework.boot.ApplicationRunner commsVendorSecretsGuard(
      @Value("${medmate.msg91.auth-key:}") String msg91,
      @Value("${medmate.fcm.server-key:}") String fcm,
      @Value("${medmate.sendgrid.api-key:}") String sendgrid,
      @Value("${medmate.whatsapp.access-token:}") String whatsapp) {
    return args ->
        com.nammamedmate.notification.application.NotificationWebhookAuth
            .validateVendorKeysForDeployedProfile(msg91, fcm, sendgrid, whatsapp);
  }

  @Bean
  @Profile("!prod & !staging")
  Rs256JwtService localJwtService(TokenRevocationStore revocationStore, Clock clock)
      throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new Rs256JwtService(pair.getPrivate(), pair.getPublic(), revocationStore, clock, 900);
  }

  @Bean
  @Profile({"prod", "staging"})
  Rs256JwtService deployedJwtService(
      TokenRevocationStore revocationStore,
      Clock clock,
      @Value("${medmate.jwt.private-key-pem}") String privatePem,
      @Value("${medmate.jwt.public-key-pem}") String publicPem) {
    return new Rs256JwtService(
        RsaKeyLoader.loadPrivateKeyPem(privatePem),
        RsaKeyLoader.loadPublicKeyPem(publicPem),
        revocationStore,
        clock,
        900);
  }

  @Bean
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(PresignedUrlService.class)
  PresignedUrlService localPresignedUrlService(
      @Value("${medmate.s3.bucket:local-bucket}") String bucket) {
    return new PresignedUrlService() {
      @Override
      public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
        return new PresignedUrl("https://local.invalid/" + bucket + "/" + key + "?put=1", key, ttl);
      }

      @Override
      public PresignedUrl createGetUrl(String key, Duration ttl) {
        return new PresignedUrl("https://local.invalid/" + bucket + "/" + key + "?get=1", key, ttl);
      }
    };
  }

  @Bean(destroyMethod = "close")
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(S3Presigner.class)
  S3Presigner s3Presigner() {
    return S3Presigner.create();
  }

  @Bean
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(PresignedUrlService.class)
  PresignedUrlService s3PresignedUrlService(
      S3Presigner presigner, @Value("${medmate.s3.bucket}") String bucket) {
    return new S3PresignedUrlService(presigner, bucket);
  }

  /** Staging/prod: default-credential S3 client for KYC PutObject (and future uploads). */
  @Bean
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(S3Client.class)
  S3Client s3Client() {
    return S3Client.create();
  }

  /** Helper for tests/docs — not a secret. */
  public static String toPem(String type, byte[] encoded) {
    String b64 =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
    return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----";
  }
}
