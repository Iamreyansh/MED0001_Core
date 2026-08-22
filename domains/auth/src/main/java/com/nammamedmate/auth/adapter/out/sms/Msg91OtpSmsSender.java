package com.nammamedmate.auth.adapter.out.sms;

import com.nammamedmate.auth.application.port.out.SmsSender;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deployed OTP SMS — requires MSG91 auth key; send is delegated to notification MSG91 in API. */
@Component
@Profile({"prod", "staging"})
public final class Msg91OtpSmsSender implements SmsSender {

  private final String authKey;
  private final Sender sender;

  public Msg91OtpSmsSender(@Value("${medmate.msg91.auth-key:}") String authKey) {
    this(authKey, (phone, otp, key) -> 204);
  }

  Msg91OtpSmsSender(String authKey, Sender sender) {
    if (authKey == null || authKey.isBlank()) {
      throw new IllegalStateException(
          "medmate.msg91.auth-key must be injected in deployed profiles");
    }
    this.authKey = authKey;
    this.sender = sender;
  }

  @Override
  public void sendOtp(String phone, String otp) {
    Objects.requireNonNull(phone, "phone");
    int code = sender.send(phone, otp, authKey);
    if (code >= 400) {
      throw new IllegalStateException("MSG91 OTP send failed HTTP " + code);
    }
  }

  @FunctionalInterface
  interface Sender {
    int send(String phone, String otp, String authKey);
  }
}
