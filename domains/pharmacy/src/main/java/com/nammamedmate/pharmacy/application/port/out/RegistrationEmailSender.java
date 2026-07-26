package com.nammamedmate.pharmacy.application.port.out;

public interface RegistrationEmailSender {

  void sendOtp(String email, String otp);
}
