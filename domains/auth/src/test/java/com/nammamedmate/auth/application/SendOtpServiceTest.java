package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.application.port.out.SmsSender;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class SendOtpServiceTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-07-25T08:00:00Z"));
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);
  private FakeOtpStore store;
  private RecordingSmsSender sms;
  private InMemoryRateLimiter limiter;
  private SendOtpService service;

  @BeforeEach
  void setUp() {
    store = new FakeOtpStore();
    sms = new RecordingSmsSender();
    limiter = new InMemoryRateLimiter(clock);
    service = new SendOtpService(store, sms, limiter, encoder, clock);
  }

  @Test
  void sendCreatesHashedSessionWithTenMinuteExpiry() {
    SendOtpResult result =
        service.send(new SendOtpCommand("+919876543210", "{\"platform\":\"android\"}", "1.2.3.4"));

    assertThat(result.phone()).isEqualTo("+919876543210");
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-07-25T08:10:00Z"));
    assertThat(result.resendAllowedAt()).isEqualTo(Instant.parse("2026-07-25T08:01:00Z"));
    assertThat(result.attemptsRemaining()).isEqualTo(3);
    OtpSessionRecord saved = store.byId.get(result.sessionId());
    assertThat(saved.otpHash()).isNotEqualTo("plaintext");
    assertThat(encoder.matches("000000", saved.otpHash()) || saved.otpHash().startsWith("$2a$"))
        .isTrue();
    assertThat(sms.lastPhone).isEqualTo("+919876543210");
    assertThat(sms.lastOtp).hasSize(6);
  }

  @Test
  void rejectsInvalidPhone() {
    assertThatThrownBy(() -> service.send(new SendOtpCommand("9876543210", null, "1.1.1.1")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void fourthSendInHourIsRateLimited() {
    SendOtpCommand cmd = new SendOtpCommand("+919876543210", null, "1.1.1.1");
    service.send(cmd);
    service.send(cmd);
    service.send(cmd);
    assertThatThrownBy(() -> service.send(cmd))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("OTP_RATE_LIMITED");
              assertThat(app.httpStatus()).isEqualTo(429);
              assertThat(app.retryAfterSeconds()).isPositive();
            });
  }

  @Test
  void ipRateLimitReturnsIpRateLimited() {
    for (int i = 0; i < 10; i++) {
      service.send(new SendOtpCommand("+91987654321" + (i % 10), null, "9.9.9.9"));
    }
    assertThatThrownBy(() -> service.send(new SendOtpCommand("+919811111111", null, "9.9.9.9")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void cooldownBlocksSend() {
    limiter.putCooldown("otp:cooldown:+919876543210", 1800);
    assertThatThrownBy(() -> service.send(new SendOtpCommand("+919876543210", null, "1.1.1.1")))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("OTP_RATE_LIMITED");
              assertThat(app.retryAfterSeconds()).isEqualTo(1800);
            });
  }

  @Test
  void magicPhoneSkipsSms() {
    service.send(new SendOtpCommand("+919999900001", null, "1.1.1.1"));
    assertThat(sms.lastPhone).isNull();
  }

  @Test
  void smsFailureReturnsGatewayError() {
    service =
        new SendOtpService(
            store,
            (phone, otp) -> {
              throw new RuntimeException("down");
            },
            limiter,
            encoder,
            clock);
    assertThatThrownBy(() -> service.send(new SendOtpCommand("+919876543210", null, "1.1.1.1")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SMS_GATEWAY_ERROR");
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

  private static final class RecordingSmsSender implements SmsSender {
    String lastPhone;
    String lastOtp;

    @Override
    public void sendOtp(String phone, String otp) {
      this.lastPhone = phone;
      this.lastOtp = otp;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

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
