package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.Rs256JwtService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class VerifyOtpServiceTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-07-25T08:05:00Z"));
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);
  private FakeOtpStore otpStore;
  private FakeCustomerStore customerStore;
  private FakeSessionStore sessionStore;
  private InMemoryRateLimiter limiter;
  private VerifyOtpService service;
  private String otpHash;

  @BeforeEach
  void setUp() throws Exception {
    otpStore = new FakeOtpStore();
    customerStore = new FakeCustomerStore();
    sessionStore = new FakeSessionStore();
    limiter = new InMemoryRateLimiter(clock);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    Rs256JwtService jwt =
        new Rs256JwtService(
            pair.getPrivate(),
            pair.getPublic(),
            new InMemoryTokenRevocationStore(clock),
            clock,
            900);
    service =
        new VerifyOtpService(otpStore, customerStore, sessionStore, limiter, encoder, jwt, clock);
    otpHash = encoder.encode("654321");
  }

  @Test
  void verifySuccessIssuesTokensAndMarksVerified() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);

    VerifyOtpResult result =
        service.verify(
            new VerifyOtpCommand(
                sessionId, "+919876543210", "654321", "fcm-token", "1.1.1.1", "ua"));

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.tokenType()).isEqualTo("Bearer");
    assertThat(result.accessTokenExpiresIn()).isEqualTo(900);
    assertThat(result.refreshTokenExpiresIn()).isEqualTo(2_592_000);
    assertThat(result.newUser()).isTrue();
    assertThat(result.customer().phone()).isEqualTo("+919876543210");
    assertThat(result.customer().deviceTokens()).containsExactly("fcm-token");
    assertThat(otpStore.byId.get(sessionId).verifiedAt()).isEqualTo(clock.instant());
    assertThat(sessionStore.saved).hasSize(1);
  }

  @Test
  void expiredReturnsOtpExpired() {
    UUID sessionId =
        seedSession("+919876543210", otpHash, Instant.parse("2026-07-25T08:00:00Z"), null, null);
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "654321", null, "1.1.1.1", null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_EXPIRED");
  }

  @Test
  void threeFailuresLockSessionAndSetCooldown() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () ->
                  service.verify(
                      new VerifyOtpCommand(
                          sessionId, "+919876543210", "000000", null, "1.1.1.1", null)))
          .extracting(ex -> ((AppException) ex).code())
          .isEqualTo("OTP_INVALID");
    }
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "000000", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_LOCKED");
    assertThat(otpStore.byId.get(sessionId).lockedAt()).isNotNull();
    assertThat(limiter.cooldownRemainingSeconds("otp:cooldown:+919876543210")).isEqualTo(1800);

    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_LOCKED");
  }

  @Test
  void alreadyUsedReturnsConflict() {
    UUID sessionId =
        seedSession("+919876543210", otpHash, null, Instant.parse("2026-07-25T08:01:00Z"), null);
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_ALREADY_USED");
  }

  @Test
  void missingSessionReturnsNotFound() {
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        Ids.newId(), "+919876543210", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_NOT_FOUND");
  }

  @Test
  void magicOtpAcceptedForTestRange() {
    UUID sessionId = seedSession("+919999900050", encoder.encode("999999"), null, null, null);
    VerifyOtpResult result =
        service.verify(
            new VerifyOtpCommand(sessionId, "+919999900050", "123456", null, "1.1.1.1", null));
    assertThat(result.newUser()).isTrue();
  }

  @Test
  void existingCustomerNotNewUserAndDeviceTokenReplaced() {
    CustomerRecord existing =
        new CustomerRecord(
            Ids.newId(),
            "+919876543210",
            List.of("old-token"),
            "Ramesh",
            null,
            null,
            "MALE",
            "kn",
            "LOYAL",
            12550L,
            38,
            Instant.parse("2025-01-10T06:30:00Z"));
    customerStore.byPhone.put(existing.phone(), existing);
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);

    VerifyOtpResult result =
        service.verify(
            new VerifyOtpCommand(
                sessionId, "+919876543210", "654321", "new-token", "1.1.1.1", null));

    assertThat(result.newUser()).isFalse();
    assertThat(result.customer().deviceTokens()).containsExactly("new-token");
    assertThat(result.customer().name()).isEqualTo("Ramesh");
  }

  @Test
  void phoneMismatchAndValidationErrors() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919811111111", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(null, "+919876543210", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void verifyIpRateLimited() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(
              () ->
                  service.verify(
                      new VerifyOtpCommand(
                          sessionId, "+919876543210", "000000", null, "8.8.8.8", null)))
          .isInstanceOf(AppException.class);
    }
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "654321", null, "8.8.8.8", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void sha256HexIsDeterministicAndFailsClosed() throws Exception {
    assertThat(service.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    VerifyOtpService broken =
        new VerifyOtpService(
            otpStore,
            customerStore,
            sessionStore,
            limiter,
            encoder,
            serviceJwt(),
            clock,
            new java.security.SecureRandom(),
            () -> {
              throw new java.security.NoSuchAlgorithmException("missing");
            });
    assertThatThrownBy(() -> broken.sha256Hex("abc"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256");
  }

  @Test
  void validationCoversNullOtpAndBadFormat() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(sessionId, "bad", "654321", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(sessionId, "+919876543210", null, null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919876543210", "12345", null, "1.1.1.1", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void newUserWithoutDeviceToken() {
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);
    VerifyOtpResult result =
        service.verify(
            new VerifyOtpCommand(sessionId, "+919876543210", "654321", null, "1.1.1.1", null));
    assertThat(result.newUser()).isTrue();
    assertThat(result.customer().deviceTokens()).isEmpty();

    otpStore.byId.clear();
    customerStore.byPhone.clear();
    UUID sessionId2 = seedSession("+919811122233", otpHash, null, null, null);
    VerifyOtpResult blank =
        service.verify(
            new VerifyOtpCommand(sessionId2, "+919811122233", "654321", "  ", "1.1.1.1", null));
    assertThat(blank.customer().deviceTokens()).isEmpty();
  }

  @Test
  void blankOrNullDeviceTokenLeavesExistingTokensAndDefaultsIp() {
    CustomerRecord existing =
        new CustomerRecord(
            Ids.newId(),
            "+919876543210",
            List.of("keep"),
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            Instant.parse("2025-01-10T06:30:00Z"));
    customerStore.byPhone.put(existing.phone(), existing);
    UUID sessionId = seedSession("+919876543210", otpHash, null, null, null);

    VerifyOtpResult blankToken =
        service.verify(
            new VerifyOtpCommand(sessionId, "+919876543210", "654321", "  ", null, null));
    assertThat(blankToken.customer().deviceTokens()).containsExactly("keep");
    assertThat(sessionStore.saved.getFirst().ipAddress()).isEqualTo("0.0.0.0");

    // re-seed for null device token path
    otpStore.byId.clear();
    sessionStore.saved.clear();
    UUID sessionId2 = seedSession("+919876543210", otpHash, null, null, null);
    VerifyOtpResult nullToken =
        service.verify(
            new VerifyOtpCommand(sessionId2, "+919876543210", "654321", null, "2.2.2.2", null));
    assertThat(nullToken.customer().deviceTokens()).containsExactly("keep");
  }

  private Rs256JwtService serviceJwt() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new Rs256JwtService(
        pair.getPrivate(), pair.getPublic(), new InMemoryTokenRevocationStore(clock), clock, 900);
  }

  private UUID seedSession(
      String phone, String hash, Instant expiresAt, Instant verifiedAt, Instant lockedAt) {
    UUID id = Ids.newId();
    Instant created = Instant.parse("2026-07-25T08:00:00Z");
    Instant expires = expiresAt == null ? Instant.parse("2026-07-25T08:10:00Z") : expiresAt;
    otpStore.save(
        new OtpSessionRecord(id, phone, hash, 0, null, expires, verifiedAt, lockedAt, created));
    return id;
  }

  private static final class FakeOtpStore implements OtpSessionStore {
    final Map<UUID, OtpSessionRecord> byId = new ConcurrentHashMap<>();

    @Override
    public OtpSessionRecord save(OtpSessionRecord session) {
      byId.put(session.id(), session);
      return session;
    }

    @Override
    public Optional<OtpSessionRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }
  }

  private static final class FakeCustomerStore implements CustomerStore {
    final Map<String, CustomerRecord> byPhone = new ConcurrentHashMap<>();
    final Map<UUID, CustomerRecord> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<CustomerRecord> findByPhone(String phone) {
      return Optional.ofNullable(byPhone.get(phone));
    }

    @Override
    public Optional<CustomerRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public CustomerRecord save(CustomerRecord customer) {
      byPhone.put(customer.phone(), customer);
      byId.put(customer.id(), customer);
      return customer;
    }
  }

  private static final class FakeSessionStore implements AuthSessionStore {
    final List<AuthSessionRecord> saved = new ArrayList<>();

    @Override
    public AuthSessionRecord save(AuthSessionRecord session) {
      saved.add(session);
      return session;
    }

    @Override
    public Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash) {
      return saved.stream().filter(s -> s.refreshTokenHash().equals(refreshTokenHash)).findFirst();
    }

    @Override
    public Optional<AuthSessionRecord> findById(UUID id) {
      return saved.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    @Override
    public int markRotatedIfActive(UUID id, Instant rotatedAt) {
      return 0;
    }

    @Override
    public int revokeIfActive(UUID id, Instant revokedAt) {
      return 0;
    }

    @Override
    public int revokeAllForUser(UUID userId, Instant revokedAt) {
      return 0;
    }

    @Override
    public List<AuthSessionRecord> listActiveByUserId(
        UUID userId, Instant now, int page, int limit) {
      return List.of();
    }

    @Override
    public long countActiveByUserId(UUID userId, Instant now) {
      return 0;
    }
  }

  private static final class MutableClock extends Clock {
    private final Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
