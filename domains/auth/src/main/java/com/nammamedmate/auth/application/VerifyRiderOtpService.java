package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.auth.domain.PhoneNumbers;
import com.nammamedmate.auth.domain.RefreshTokens;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class VerifyRiderOtpService {

  static final int MAX_ATTEMPTS = 3;
  static final int COOLDOWN_SECONDS = 1800;
  static final int VERIFY_IP_LIMIT = 10;
  static final int VERIFY_IP_WINDOW_SECONDS = 60;
  static final long ACCESS_TTL_SECONDS = 900L;
  static final long REFRESH_TTL_SECONDS = 2_592_000L;

  private final OtpSessionStore otpSessionStore;
  private final RiderAccountPort riderAccounts;
  private final AuthSessionStore authSessionStore;
  private final RateLimiter rateLimiter;
  private final PasswordEncoder passwordEncoder;
  private final Rs256JwtService jwtService;
  private final Clock clock;
  private final SecureRandom secureRandom;

  @Autowired
  public VerifyRiderOtpService(
      OtpSessionStore otpSessionStore,
      RiderAccountPort riderAccounts,
      AuthSessionStore authSessionStore,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Rs256JwtService jwtService,
      Clock clock) {
    this(
        otpSessionStore,
        riderAccounts,
        authSessionStore,
        rateLimiter,
        passwordEncoder,
        jwtService,
        clock,
        new SecureRandom());
  }

  VerifyRiderOtpService(
      OtpSessionStore otpSessionStore,
      RiderAccountPort riderAccounts,
      AuthSessionStore authSessionStore,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Rs256JwtService jwtService,
      Clock clock,
      SecureRandom secureRandom) {
    this.otpSessionStore = otpSessionStore;
    this.riderAccounts = riderAccounts;
    this.authSessionStore = authSessionStore;
    this.rateLimiter = rateLimiter;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.clock = clock;
    this.secureRandom = secureRandom;
  }

  public VerifyRiderOtpResult verify(VerifyOtpCommand command) {
    if (command.sessionId() == null
        || !PhoneNumbers.isValidIndianMobile(command.phone())
        || command.otp() == null
        || !command.otp().matches("\\d{6}")) {
      throw new AppException("VALIDATION_ERROR", "Missing or malformed fields", 400);
    }

    String ipKey = "rider-otp:ip:verify:" + command.clientIp() + ":count";
    if (!rateLimiter.tryAcquire(ipKey, VERIFY_IP_LIMIT, VERIFY_IP_WINDOW_SECONDS)) {
      int retry =
          Math.max(
              1,
              rateLimiter.secondsUntilAvailable(ipKey, VERIFY_IP_LIMIT, VERIFY_IP_WINDOW_SECONDS));
      throw new AppException("IP_RATE_LIMITED", "IP verify rate limit exceeded", 429, retry);
    }

    OtpSessionRecord session =
        otpSessionStore
            .findById(command.sessionId())
            .orElseThrow(
                () -> new AppException("OTP_SESSION_NOT_FOUND", "OTP session not found", 404));

    if (!session.phone().equals(command.phone())) {
      throw new AppException("VALIDATION_ERROR", "Phone does not match session", 400);
    }
    if (session.verifiedAt() != null) {
      throw new AppException("OTP_ALREADY_USED", "OTP session already verified", 409);
    }
    if (session.lockedAt() != null) {
      throw new AppException("OTP_SESSION_LOCKED", "OTP session is locked", 400);
    }

    Instant now = clock.instant();
    if (!now.isBefore(session.expiresAt())) {
      throw new AppException("OTP_EXPIRED", "OTP session expired", 400);
    }

    boolean otpOk =
        MagicOtp.matches(command.phone(), command.otp())
            || passwordEncoder.matches(command.otp(), session.otpHash());
    if (!otpOk) {
      int attempts = session.attempts() + 1;
      Instant lockedAt = attempts >= MAX_ATTEMPTS ? now : null;
      otpSessionStore.save(
          new OtpSessionRecord(
              session.id(),
              session.phone(),
              session.otpHash(),
              attempts,
              session.deviceInfoJson(),
              session.expiresAt(),
              session.verifiedAt(),
              lockedAt,
              session.createdAt()));
      if (lockedAt != null) {
        rateLimiter.putCooldown("otp:cooldown:" + session.phone(), COOLDOWN_SECONDS);
        throw new AppException("OTP_SESSION_LOCKED", "OTP session is locked", 400);
      }
      throw new AppException("OTP_INVALID", "OTP does not match", 400);
    }

    otpSessionStore.save(
        new OtpSessionRecord(
            session.id(),
            session.phone(),
            session.otpHash(),
            session.attempts(),
            session.deviceInfoJson(),
            session.expiresAt(),
            now,
            session.lockedAt(),
            session.createdAt()));

    RiderAccount rider =
        riderAccounts
            .findByPhone(command.phone())
            .orElseThrow(
                () ->
                    new AppException("RIDER_NOT_FOUND", "No rider registered for this phone", 404));

    if ("BLOCKED".equals(rider.status())) {
      throw new AppException("UNAUTHORIZED", "Rider account is blocked", 401);
    }

    String refreshToken = opaqueRefreshToken();
    Instant refreshExpires = now.plus(REFRESH_TTL_SECONDS, ChronoUnit.SECONDS);
    authSessionStore.save(
        AuthSessionRecord.active(
            Ids.newId(),
            rider.id(),
            "rider",
            RefreshTokens.sha256Hex(refreshToken),
            "full",
            session.deviceInfoJson(),
            command.clientIp() == null ? "0.0.0.0" : command.clientIp(),
            command.userAgent(),
            now,
            now,
            refreshExpires,
            null));

    String accessToken =
        jwtService.issueAccessToken(
            new JwtClaims(
                rider.id(), AuthRole.RIDER, null, TokenScope.FULL, Ids.newId().toString()));

    return new VerifyRiderOtpResult(
        accessToken, refreshToken, "Bearer", ACCESS_TTL_SECONDS, REFRESH_TTL_SECONDS, rider);
  }

  private String opaqueRefreshToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
