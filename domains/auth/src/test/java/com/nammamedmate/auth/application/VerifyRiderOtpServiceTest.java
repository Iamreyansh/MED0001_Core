package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
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

class VerifyRiderOtpServiceTest {

  private FakeOtp otps;
  private FakeRiders riders;
  private FakeSessions sessions;
  private VerifyRiderOtpService service;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);
  private UUID sessionId;
  private UUID riderId;

  @BeforeEach
  void setUp() throws Exception {
    otps = new FakeOtp();
    riders = new FakeRiders();
    sessions = new FakeSessions();
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    Rs256JwtService jwt =
        new Rs256JwtService(
            pair.getPrivate(), pair.getPublic(), new InMemoryTokenRevocationStore(), clock, 900);
    service =
        new VerifyRiderOtpService(
            otps,
            riders,
            sessions,
            new InMemoryRateLimiter(clock),
            new BCryptPasswordEncoder(4),
            jwt,
            clock);
    sessionId = Ids.newId();
    riderId = Ids.newId();
    otps.byId.put(
        sessionId,
        new OtpSessionRecord(
            sessionId,
            "+919999900010",
            "hash",
            0,
            null,
            clock.instant().plusSeconds(600),
            null,
            null,
            clock.instant()));
    riders.byPhone.put(
        "+919999900010",
        new RiderAccount(riderId, "+919999900010", "Ravi", "ACTIVE", "APPROVED", null, null, null));
  }

  @Test
  void verifyIssuesRiderToken() {
    VerifyRiderOtpResult result =
        service.verify(
            new VerifyOtpCommand(sessionId, "+919999900010", "123456", null, "1.1.1.1", "ua"));
    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.rider().id()).isEqualTo(riderId);
    assertThat(sessions.saved).isNotEmpty();
  }

  @Test
  void ac008_blockedRiderGets401() {
    riders.byPhone.put(
        "+919999900010",
        new RiderAccount(
            riderId, "+919999900010", "Ravi", "BLOCKED", "APPROVED", null, null, null));
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("UNAUTHORIZED");
              assertThat(ae.httpStatus()).isEqualTo(401);
            });
  }

  @Test
  void unknownRider() {
    riders.byPhone.clear();
    assertThatThrownBy(
            () ->
                service.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
  }

  static final class FakeOtp implements OtpSessionStore {
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

  static final class FakeRiders implements RiderAccountPort {
    final Map<String, RiderAccount> byPhone = new ConcurrentHashMap<>();

    @Override
    public Optional<RiderAccount> findByPhone(String phone) {
      return Optional.ofNullable(byPhone.get(phone));
    }

    @Override
    public Optional<RiderAccount> findById(UUID id) {
      return byPhone.values().stream().filter(r -> r.id().equals(id)).findFirst();
    }
  }

  static final class FakeSessions implements AuthSessionStore {
    final List<AuthSessionRecord> saved = new ArrayList<>();

    @Override
    public AuthSessionRecord save(AuthSessionRecord session) {
      saved.add(session);
      return session;
    }

    @Override
    public Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash) {
      return Optional.empty();
    }

    @Override
    public Optional<AuthSessionRecord> findById(UUID id) {
      return Optional.empty();
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
}
