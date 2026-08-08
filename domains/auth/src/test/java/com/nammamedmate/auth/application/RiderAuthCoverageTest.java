package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.adapter.in.web.RiderAuthController;
import com.nammamedmate.auth.adapter.in.web.dto.DeviceInfoRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpRequest;
import com.nammamedmate.auth.adapter.out.persistence.JdbcRiderAccountAdapter;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
import com.nammamedmate.auth.application.port.out.SmsSender;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class RiderAuthCoverageTest {

  private FakeOtp otps;
  private FakeRiders riders;
  private FakeSessions sessions;
  private VerifyRiderOtpService verify;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);
  private Rs256JwtService jwt;
  private UUID sessionId;
  private UUID riderId;
  private InMemoryRateLimiter limiter;

  @BeforeEach
  void setUp() throws Exception {
    otps = new FakeOtp();
    riders = new FakeRiders();
    sessions = new FakeSessions();
    limiter = new InMemoryRateLimiter(clock);
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    jwt =
        new Rs256JwtService(
            pair.getPrivate(), pair.getPublic(), new InMemoryTokenRevocationStore(), clock, 900);
    verify =
        new VerifyRiderOtpService(
            otps, riders, sessions, limiter, new BCryptPasswordEncoder(4), jwt, clock);
    riderId = Ids.newId();
    seedSession("+919999900010");
    riders.byPhone.put(
        "+919999900010",
        new RiderAccount(riderId, "+919999900010", "Ravi", "ACTIVE", "APPROVED", null, null, null));
  }

  @Test
  void verifyBranchesAndController() {
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(null, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    seedSession("+919999900010");
    for (int i = 0; i < 2; i++) {
      try {
        verify.verify(
            new VerifyOtpCommand(sessionId, "+919999900010", "000000", null, "1.1.1.1", "ua"));
      } catch (AppException ignored) {
      }
    }
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919999900010", "000000", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_LOCKED");

    UUID sid2 = Ids.newId();
    otps.byId.put(
        sid2,
        new OtpSessionRecord(
            sid2,
            "+919999900010",
            "hash",
            0,
            null,
            clock.instant().minusSeconds(1),
            null,
            null,
            clock.instant()));
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sid2, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_EXPIRED");

    UUID sid3 = Ids.newId();
    otps.byId.put(
        sid3,
        new OtpSessionRecord(
            sid3,
            "+919999900011",
            "hash",
            0,
            null,
            clock.instant().plusSeconds(600),
            null,
            null,
            clock.instant()));
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sid3, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID sid4 = Ids.newId();
    otps.byId.put(
        sid4,
        new OtpSessionRecord(
            sid4,
            "+919999900010",
            "hash",
            0,
            null,
            clock.instant().plusSeconds(600),
            clock.instant(),
            null,
            clock.instant()));
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sid4, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_ALREADY_USED");

    UUID sid5 = Ids.newId();
    otps.byId.put(
        sid5,
        new OtpSessionRecord(
            sid5,
            "+919999900010",
            "hash",
            0,
            null,
            clock.instant().plusSeconds(600),
            null,
            clock.instant(),
            clock.instant()));
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sid5, "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_LOCKED");

    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(
                        Ids.newId(), "+919999900010", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_SESSION_NOT_FOUND");

    seedSession("+919999900010");
    verify.verify(new VerifyOtpCommand(sessionId, "+919999900010", "123456", null, null, "ua"));

    InMemoryRateLimiter tight = new InMemoryRateLimiter(clock);
    VerifyRiderOtpService limited =
        new VerifyRiderOtpService(
            otps, riders, sessions, tight, new BCryptPasswordEncoder(4), jwt, clock);
    for (int i = 0; i < VerifyRiderOtpService.VERIFY_IP_LIMIT; i++) {
      tight.tryAcquire(
          "rider-otp:ip:verify:9.9.9.9:count", VerifyRiderOtpService.VERIFY_IP_LIMIT, 60);
    }
    seedSession("+919999900010");
    assertThatThrownBy(
            () ->
                limited.verify(
                    new VerifyOtpCommand(
                        sessionId, "+919999900010", "123456", null, "9.9.9.9", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IP_RATE_LIMITED");

    SendOtpService send =
        new SendOtpService(
            otps,
            new SmsSender() {
              @Override
              public void sendOtp(String phone, String otp) {}
            },
            limiter,
            new BCryptPasswordEncoder(4),
            clock);
    RiderAuthController ctrl = new RiderAuthController(send, verify, new ObjectMapper());
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8");
    assertThat(
            ctrl.sendOtp(
                    new SendOtpRequest("+919999900012", new DeviceInfoRequest("a", "b", "c")), req)
                .success())
        .isTrue();
    assertThat(ctrl.sendOtp(new SendOtpRequest("+919999900013", null), req).success()).isTrue();
    MockHttpServletRequest bare = new MockHttpServletRequest();
    bare.setRemoteAddr("");
    seedSession("+919999900010");
    assertThat(
            ctrl.verifyOtp(new VerifyOtpRequest(sessionId, "+919999900010", "123456", null), bare)
                .success())
        .isTrue();
    MockHttpServletRequest blankFwd = new MockHttpServletRequest();
    blankFwd.addHeader("X-Forwarded-For", "   ");
    blankFwd.setRemoteAddr("10.0.0.2");
    seedSession("+919999900010");
    assertThat(
            ctrl.verifyOtp(
                    new VerifyOtpRequest(sessionId, "+919999900010", "123456", null), blankFwd)
                .success())
        .isTrue();
    MockHttpServletRequest nullRemote = new MockHttpServletRequest();
    nullRemote.setRemoteAddr(null);
    seedSession("+919999900010");
    assertThat(
            ctrl.verifyOtp(
                    new VerifyOtpRequest(sessionId, "+919999900010", "123456", null), nullRemote)
                .success())
        .isTrue();
  }

  @Test
  void moreValidationAndPasswordOtpAndControllerEdges() throws Exception {
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sessionId, "9876543210", "123456", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sessionId, "+919999900010", null, null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                verify.verify(
                    new VerifyOtpCommand(sessionId, "+919999900010", "12", null, "1.1.1.1", "ua")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    String phone = "+918765432109";
    UUID sid = Ids.newId();
    otps.byId.put(
        sid,
        new OtpSessionRecord(
            sid,
            phone,
            encoder.encode("654321"),
            0,
            null,
            clock.instant().plusSeconds(600),
            null,
            null,
            clock.instant()));
    riders.byPhone.put(
        phone,
        new RiderAccount(
            Ids.newId(), phone, "X", "PENDING_KYC", "NOT_SUBMITTED", null, null, null));
    VerifyRiderOtpService withEncoder =
        new VerifyRiderOtpService(otps, riders, sessions, limiter, encoder, jwt, clock);
    assertThat(
            withEncoder
                .verify(new VerifyOtpCommand(sid, phone, "654321", null, "2.2.2.2", "ua"))
                .accessToken())
        .isNotBlank();

    ObjectMapper broken =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("boom") {};
          }
        };
    SendOtpService send =
        new SendOtpService(
            otps,
            new SmsSender() {
              @Override
              public void sendOtp(String p, String otp) {}
            },
            limiter,
            encoder,
            clock);
    RiderAuthController ctrl = new RiderAuthController(send, verify, broken);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1");
    assertThat(
            ctrl.sendOtp(
                    new SendOtpRequest("+919999900014", new DeviceInfoRequest("a", "b", "c")), req)
                .success())
        .isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcAdapter() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              var rs = mock(java.sql.ResultSet.class);
              UUID id = Ids.newId();
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("phone")).thenReturn("+919999900010");
              when(rs.getString("name")).thenReturn("R");
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("kyc_status")).thenReturn("APPROVED");
              when(rs.getString("email")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcRiderAccountAdapter adapter = new JdbcRiderAccountAdapter(jdbc);
    assertThat(adapter.findByPhone("+919999900010")).isPresent();
    assertThat(adapter.findById(Ids.newId())).isPresent();
  }

  private void seedSession(String phone) {
    sessionId = Ids.newId();
    otps.byId.put(
        sessionId,
        new OtpSessionRecord(
            sessionId,
            phone,
            "hash",
            0,
            null,
            clock.instant().plusSeconds(600),
            null,
            null,
            clock.instant()));
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
