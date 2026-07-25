package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import com.nammamedmate.auth.application.port.out.SmsSender;
import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.auth.domain.PhoneNumbers;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SendOtpService {

  static final int PHONE_LIMIT = 3;
  static final int PHONE_WINDOW_SECONDS = 3600;
  static final int IP_LIMIT = 10;
  static final int IP_WINDOW_SECONDS = 3600;
  static final int OTP_TTL_MINUTES = 10;
  static final int RESEND_SECONDS = 60;
  static final int ATTEMPTS_REMAINING = 3;
  static final int COOLDOWN_SECONDS = 1800;

  private final OtpSessionStore otpSessionStore;
  private final SmsSender smsSender;
  private final RateLimiter rateLimiter;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final SecureRandom secureRandom;

  @Autowired
  public SendOtpService(
      OtpSessionStore otpSessionStore,
      SmsSender smsSender,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this(otpSessionStore, smsSender, rateLimiter, passwordEncoder, clock, new SecureRandom());
  }

  SendOtpService(
      OtpSessionStore otpSessionStore,
      SmsSender smsSender,
      RateLimiter rateLimiter,
      PasswordEncoder passwordEncoder,
      Clock clock,
      SecureRandom secureRandom) {
    this.otpSessionStore = otpSessionStore;
    this.smsSender = smsSender;
    this.rateLimiter = rateLimiter;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.secureRandom = secureRandom;
  }

  public SendOtpResult send(SendOtpCommand command) {
    String phone = command.phone();
    if (!PhoneNumbers.isValidIndianMobile(phone)) {
      throw new AppException("VALIDATION_ERROR", "Invalid Indian mobile number", 400);
    }

    String cooldownKey = "otp:cooldown:" + phone;
    int cooldown = rateLimiter.cooldownRemainingSeconds(cooldownKey);
    if (cooldown > 0) {
      throw new AppException("OTP_RATE_LIMITED", "Phone is in cooldown", 429, cooldown);
    }

    String ipKey = "otp:ip:" + command.clientIp() + ":count";
    if (!rateLimiter.tryAcquire(ipKey, IP_LIMIT, IP_WINDOW_SECONDS)) {
      int retry =
          Math.max(1, rateLimiter.secondsUntilAvailable(ipKey, IP_LIMIT, IP_WINDOW_SECONDS));
      throw new AppException("IP_RATE_LIMITED", "IP send-OTP rate limit exceeded", 429, retry);
    }

    String phoneKey = "otp:phone:" + phone + ":count";
    if (!rateLimiter.tryAcquire(phoneKey, PHONE_LIMIT, PHONE_WINDOW_SECONDS)) {
      int retry =
          Math.max(
              1, rateLimiter.secondsUntilAvailable(phoneKey, PHONE_LIMIT, PHONE_WINDOW_SECONDS));
      throw new AppException("OTP_RATE_LIMITED", "Phone OTP rate limit exceeded", 429, retry);
    }

    Instant now = clock.instant();
    String otp = generateOtp();
    String hash = passwordEncoder.encode(otp);
    OtpSessionRecord session =
        new OtpSessionRecord(
            Ids.newId(),
            phone,
            hash,
            0,
            command.deviceInfoJson(),
            now.plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES),
            null,
            null,
            now);
    otpSessionStore.save(session);

    if (!MagicOtp.isTestPhone(phone)) {
      try {
        smsSender.sendOtp(phone, otp);
      } catch (RuntimeException ex) {
        throw new AppException("SMS_GATEWAY_ERROR", "SMS provider unavailable", 503);
      }
    }

    return new SendOtpResult(
        session.id(),
        phone,
        session.expiresAt(),
        now.plusSeconds(RESEND_SECONDS),
        ATTEMPTS_REMAINING);
  }

  private String generateOtp() {
    int value = secureRandom.nextInt(1_000_000);
    return String.format("%06d", value);
  }
}
