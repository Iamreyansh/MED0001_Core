package com.nammamedmate.pharmacy.adapter.out.email;

import com.nammamedmate.pharmacy.application.port.out.RegistrationEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub until EPIC-017 email service. Never logs plaintext OTP in production paths — IT uses magic.
 */
@Component
public class LoggingRegistrationEmailSender implements RegistrationEmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingRegistrationEmailSender.class);

  @Override
  public void sendOtp(String email, String otp) {
    // ids/length only — never log email or OTP (PII / secret)
    log.info("pharmacy registration OTP dispatched length={}", otp == null ? 0 : otp.length());
  }
}
