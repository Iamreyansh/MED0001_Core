package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Offline DigiLocker OAuth stub — builds authorize URL and returns deterministic documents. */
public final class StubDigiLockerClient implements DigiLockerClientPort {

  private final String clientId;
  private final String baseAuthorizeUrl;

  public StubDigiLockerClient() {
    this("NM_CLIENT", "https://api.digitallocker.gov.in/public/oauth2/1/authorize");
  }

  public StubDigiLockerClient(String clientId, String baseAuthorizeUrl) {
    this.clientId = clientId == null || clientId.isBlank() ? "NM_CLIENT" : clientId;
    this.baseAuthorizeUrl =
        baseAuthorizeUrl == null || baseAuthorizeUrl.isBlank()
            ? "https://api.digitallocker.gov.in/public/oauth2/1/authorize"
            : baseAuthorizeUrl;
  }

  @Override
  public AuthUrl buildAuthorizeUrl(String redirectUri, String state) {
    String url =
        baseAuthorizeUrl
            + "?response_type=code"
            + "&client_id="
            + enc(clientId)
            + "&state="
            + enc(state)
            + "&redirect_uri="
            + enc(redirectUri);
    return new AuthUrl(url, state, 600);
  }

  @Override
  public DigiLockerDocuments exchangeCode(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code is required");
    }
    return new DigiLockerDocuments(
        true,
        "Rajesh Kumar",
        LocalDate.of(1985, 3, 15),
        "12, MG Road, Bangalore - 560001",
        List.of("AADHAAR", "DRIVING_LICENCE"));
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
