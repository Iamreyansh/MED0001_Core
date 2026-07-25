package com.nammamedmate.auth.domain;

public final class MagicOtp {

  public static final String CODE = "123456";
  private static final long RANGE_START = 919999900000L;
  private static final long RANGE_END = 919999900099L;

  private MagicOtp() {}

  public static boolean isTestPhone(String phone) {
    if (phone == null || !phone.startsWith("+")) {
      return false;
    }
    try {
      long n = Long.parseLong(phone.substring(1));
      return n >= RANGE_START && n <= RANGE_END;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  public static boolean matches(String phone, String otp) {
    return isTestPhone(phone) && CODE.equals(otp);
  }
}
