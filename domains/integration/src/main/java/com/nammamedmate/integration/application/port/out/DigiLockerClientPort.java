package com.nammamedmate.integration.application.port.out;

import java.time.LocalDate;
import java.util.List;

public interface DigiLockerClientPort {

  record AuthUrl(String authUrl, String state, int expiresInSeconds) {}

  record DigiLockerDocuments(
      boolean aadhaarVerified,
      String nameOnAadhaar,
      LocalDate dob,
      String address,
      List<String> documentsFetched) {
    public DigiLockerDocuments {
      documentsFetched = List.copyOf(documentsFetched);
    }
  }

  AuthUrl buildAuthorizeUrl(String redirectUri, String state);

  DigiLockerDocuments exchangeCode(String code);
}
