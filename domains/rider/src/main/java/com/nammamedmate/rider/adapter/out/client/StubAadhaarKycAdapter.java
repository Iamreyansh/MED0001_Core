package com.nammamedmate.rider.adapter.out.client;

import com.nammamedmate.rider.application.port.out.AadhaarKycPort;
import java.util.UUID;

/** ponytail: Aadhaar OTP integration disabled by default; always succeeds when invoked. */
public class StubAadhaarKycAdapter implements AadhaarKycPort {

  @Override
  public boolean verify(UUID riderId, String documentNumber) {
    return riderId != null;
  }
}
