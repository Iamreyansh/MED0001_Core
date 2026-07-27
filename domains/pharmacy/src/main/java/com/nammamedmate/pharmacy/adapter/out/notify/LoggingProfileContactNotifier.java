package com.nammamedmate.pharmacy.adapter.out.notify;

import com.nammamedmate.pharmacy.application.port.out.ProfileContactNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stub until EPIC-017. Logs length only — never phone, email, or OTP. */
@Component
public class LoggingProfileContactNotifier implements ProfileContactNotifier {

  private static final Logger log = LoggerFactory.getLogger(LoggingProfileContactNotifier.class);

  @Override
  public void sendEmailOtp(String email, String otp) {
    log.info("pharmacy profile email OTP dispatched length={}", otp == null ? 0 : otp.length());
  }

  @Override
  public void sendSmsOtp(String phone, String otp) {
    log.info("pharmacy profile SMS OTP dispatched length={}", otp == null ? 0 : otp.length());
  }
}
