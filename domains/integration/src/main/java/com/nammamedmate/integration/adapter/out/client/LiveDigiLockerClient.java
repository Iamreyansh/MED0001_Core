package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Live DigiLocker OAuth client. client_secret never logged. */
public final class LiveDigiLockerClient implements DigiLockerClientPort {

  public record TokenRequest(URI uri, Map<String, String> headers, String body) {
    public TokenRequest {
      headers = Map.copyOf(headers);
    }
  }

  private final String clientId;
  private final String clientSecret;
  private final String authorizeUrl;
  private final String tokenUrl;
  private final ObjectMapper mapper;
  private final Function<TokenRequest, String> httpPost;

  public LiveDigiLockerClient(
      String clientId,
      String clientSecret,
      String authorizeUrl,
      String tokenUrl,
      ObjectMapper mapper,
      Function<TokenRequest, String> httpPost) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.authorizeUrl = authorizeUrl;
    this.tokenUrl = tokenUrl;
    this.mapper = mapper;
    this.httpPost = httpPost;
  }

  @Override
  public AuthUrl buildAuthorizeUrl(String redirectUri, String state) {
    String url =
        authorizeUrl
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
    try {
      String body =
          "grant_type=authorization_code"
              + "&code="
              + enc(code)
              + "&client_id="
              + enc(clientId)
              + "&client_secret="
              + enc(clientSecret);
      String response =
          httpPost.apply(
              new TokenRequest(
                  URI.create(tokenUrl),
                  Map.of("Content-Type", "application/x-www-form-urlencoded"),
                  body));
      JsonNode root = mapper.readTree(response);
      List<String> docs = new ArrayList<>();
      root.path("documents_fetched").forEach(n -> docs.add(n.asText()));
      if (docs.isEmpty()) {
        docs.add("AADHAAR");
      }
      return new DigiLockerDocuments(
          root.path("aadhaar_verified").asBoolean(true),
          text(root, "name_on_aadhaar"),
          parseDate(root.path("dob").asText(null)),
          text(root, "address"),
          List.copyOf(docs));
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("DIGILOCKER_UNAVAILABLE", "DigiLocker token exchange failed", 503);
    }
  }

  private static String enc(String v) {
    return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return n == null || n.isNull() ? null : n.asText();
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
  }
}
