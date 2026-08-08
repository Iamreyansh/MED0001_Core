package com.nammamedmate.rider.application.port.out;

import java.util.UUID;

/** Optional DigiLocker / Aadhaar OTP auto-KYC. Stubbed when feature flag is off. */
public interface AadhaarKycPort {

  /**
   * @return true when Aadhaar was verified successfully
   */
  boolean verify(UUID riderId, String documentNumber);
}
