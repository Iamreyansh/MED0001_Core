package com.nammamedmate.auth.application.port.out;

public interface SmsSender {

  void sendOtp(String phone, String otp);
}
