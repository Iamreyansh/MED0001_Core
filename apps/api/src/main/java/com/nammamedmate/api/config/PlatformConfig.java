package com.nammamedmate.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.OutboxStore;
import com.nammamedmate.messaging.SqsEventDispatcher;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class PlatformConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RateLimiter rateLimiter(Clock clock) {
    return new InMemoryRateLimiter(clock);
  }

  @Bean
  TokenRevocationStore tokenRevocationStore(Clock clock) {
    return new InMemoryTokenRevocationStore(clock);
  }

  @Bean
  OutboxStore outboxStore() {
    return new InMemoryOutboxStore();
  }

  @Bean
  OutboxPublisher outboxPublisher(OutboxStore store, ObjectMapper objectMapper) {
    return new OutboxPublisher(store, objectMapper);
  }

  @Bean
  SqsEventDispatcher sqsEventDispatcher(OutboxStore store) {
    return new SqsEventDispatcher(store, message -> {}, 25);
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

  /** Helper for tests/docs — not a secret. */
  public static String toPem(String type, byte[] encoded) {
    String b64 =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
    return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----";
  }
}
