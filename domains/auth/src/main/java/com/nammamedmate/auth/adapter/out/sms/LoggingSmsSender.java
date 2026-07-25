package com.nammamedmate.auth.adapter.out.sms;

import com.nammamedmate.auth.application.port.out.SmsSender;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingSmsSender implements SmsSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

  @Override
  public void sendOtp(String phone, String otp) {
    Objects.requireNonNull(phone, "phone");
    // ponytail: stub SMS — never log phone/OTP; ids-only PII rule
    log.info("otp.sms.queued otpLength={}", otp == null ? 0 : otp.length());
  }
}
