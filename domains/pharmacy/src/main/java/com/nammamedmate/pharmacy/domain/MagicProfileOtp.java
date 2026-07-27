package com.nammamedmate.pharmacy.domain;

/** Magic OTP for local profile phone/email change verification. */
public final class MagicProfileOtp {

  public static final String CODE = "123456";

  private MagicProfileOtp() {}

  public static boolean matches(String channel, String target, String otp) {
    if (!CODE.equals(otp) || channel == null || target == null) {
      return false;
    }
    return switch (channel) {
      case "EMAIL" -> MagicRegistrationOtp.isMagicEmail(target);
      case "PHONE" -> isTestPhone(target);
      default -> false;
    };
  }

  /** ponytail: pharmacy IT phones + auth test range without domain→auth dep. */
  public static boolean isTestPhone(String phone) {
    if (phone == null) {
      return false;
    }
    if (phone.startsWith("+9198111")) {
      return true;
    }
    return phone.matches("\\+9199999000\\d{2}");
  }
}
