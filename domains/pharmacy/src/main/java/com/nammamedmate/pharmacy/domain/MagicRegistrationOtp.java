package com.nammamedmate.pharmacy.domain;

/** Magic OTP for local/IT emails ending with @nammamedmate.test */
public final class MagicRegistrationOtp {

  public static final String CODE = "123456";
  public static final String EMAIL_SUFFIX = "@nammamedmate.test";

  private MagicRegistrationOtp() {}

  public static boolean isMagicEmail(String email) {
    return email != null && email.toLowerCase().endsWith(EMAIL_SUFFIX);
  }

  public static boolean matches(String email, String otp) {
    return isMagicEmail(email) && CODE.equals(otp);
  }
}
