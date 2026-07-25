package com.nammamedmate.auth.domain;

import java.util.regex.Pattern;

public final class PhoneNumbers {

  private static final Pattern INDIAN_MOBILE = Pattern.compile("^\\+91[6-9]\\d{9}$");

  private PhoneNumbers() {}

  public static boolean isValidIndianMobile(String phone) {
    return phone != null && INDIAN_MOBILE.matcher(phone).matches();
  }
}
