package com.nammamedmate.pharmacy.application.port.out;

/** Out-of-band OTP delivery for profile phone/email changes. Never put OTP in outbox. */
public interface ProfileContactNotifier {

  ProfileContactNotifier NOOP =
      new ProfileContactNotifier() {
        @Override
        public void sendEmailOtp(String email, String otp) {}

        @Override
        public void sendSmsOtp(String phone, String otp) {}
      };

  void sendEmailOtp(String email, String otp);

  void sendSmsOtp(String phone, String otp);
}
