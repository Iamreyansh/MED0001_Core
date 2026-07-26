package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.auth.domain.PhoneNumbers;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class VerifyOtpService {

  static final int MAX_ATTEMPTS = 3;
  static final int COOLDOWN_SECONDS = 1800;
  static final int VERIFY_IP_LIMIT = 10;
  static final int VERIFY_IP_WINDOW_SECONDS = 60;
  static final long ACCESS_TTL_SECONDS = 900L;
  static final long REFRESH_TTL_SECONDS = 2_592_000L;

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final OtpSessionStore otpSessionStore;
  private final CustomerStore customerStore;
  private final AuthSessionStore authSessionStore;
  private final RateLimiter rateLimiter;
  private final PasswordEncoder passwordEncoder;
  private final Rs256JwtService jwtService;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final DigestFactory digestFactory;

  @Autowired
  public VerifyOtpService(
      OtpSessionStore otpSessionStore,
      CustomerStore customerStore,
      AuthSessionStore authSessionStore,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Rs256JwtService jwtService,
      Clock clock) {
    this(
        otpSessionStore,
        customerStore,
        authSessionStore,
        rateLimiter,
        passwordEncoder,
        jwtService,
        clock,
        new SecureRandom(),
        () -> MessageDigest.getInstance("SHA-256"));
  }

  VerifyOtpService(
      OtpSessionStore otpSessionStore,
      CustomerStore customerStore,
      AuthSessionStore authSessionStore,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Rs256JwtService jwtService,
      Clock clock,
      SecureRandom secureRandom,
      DigestFactory digestFactory) {
    this.otpSessionStore = otpSessionStore;
    this.customerStore = customerStore;
    this.authSessionStore = authSessionStore;
    this.rateLimiter = rateLimiter;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.digestFactory = digestFactory;
  }

  public VerifyOtpResult verify(VerifyOtpCommand command) {
    if (command.sessionId() == null
        || !PhoneNumbers.isValidIndianMobile(command.phone())
        || command.otp() == null
        || !command.otp().matches("\\d{6}")) {
      throw new AppException("VALIDATION_ERROR", "Missing or malformed fields", 400);
    }

    String ipKey = "otp:ip:verify:" + command.clientIp() + ":count";
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

    Optional<CustomerRecord> existing = customerStore.findByPhone(command.phone());
    boolean newUser = existing.isEmpty();
    CustomerRecord customer =
        existing
            .map(c -> upsertDeviceToken(c, command.deviceToken()))
            .orElseGet(() -> createCustomer(command.phone(), command.deviceToken(), now));
    customer = customerStore.save(customer);

    String refreshToken = opaqueRefreshToken();
    Instant refreshExpires = now.plus(REFRESH_TTL_SECONDS, ChronoUnit.SECONDS);
    authSessionStore.save(
        AuthSessionRecord.active(
            Ids.newId(),
            customer.id(),
            "customer",
            sha256Hex(refreshToken),
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
                customer.id(), AuthRole.CUSTOMER, null, TokenScope.FULL, Ids.newId().toString()));

    return new VerifyOtpResult(
        accessToken,
        refreshToken,
        "Bearer",
        ACCESS_TTL_SECONDS,
        REFRESH_TTL_SECONDS,
        newUser,
        customer);
  }

  private CustomerRecord createCustomer(String phone, String deviceToken, Instant now) {
    List<String> tokens =
        deviceToken != null && !deviceToken.isBlank() ? List.of(deviceToken) : List.of();
    return new CustomerRecord(
        Ids.newId(), phone, tokens, null, null, null, null, "en", "NEW", 0L, 0, now);
  }

  private CustomerRecord upsertDeviceToken(CustomerRecord customer, String deviceToken) {
    if (deviceToken == null || deviceToken.isBlank()) {
      return customer;
    }
    // ponytail: verify has token only (no device_id); replace stored set with latest token
    return new CustomerRecord(
        customer.id(),
        customer.phone(),
        List.of(deviceToken),
        customer.name(),
        customer.avatarUrl(),
        customer.dateOfBirth(),
        customer.gender(),
        customer.preferredLanguage(),
        customer.segment(),
        customer.walletBalancePaise(),
        customer.loyaltyPoints(),
        customer.createdAt());
  }

  private String opaqueRefreshToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  String sha256Hex(String value) {
    try {
      MessageDigest digest = digestFactory.create();
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
