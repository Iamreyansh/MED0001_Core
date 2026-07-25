package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class TokenManagementIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900020";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void refreshRotateReuseMeLogoutAllAndOwnership() {
    Map<String, Object> tokens = loginCustomer(MAGIC_PHONE);
    String access = String.valueOf(tokens.get("access_token"));
    String refresh = String.valueOf(tokens.get("refresh_token"));

    ResponseEntity<Map> me =
        rest.exchange(
            baseUrl() + "/api/v1/auth/me", HttpMethod.GET, bearer(access, null), Map.class);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> meData =
        (Map<String, Object>) Objects.requireNonNull(me.getBody()).get("data");
    assertThat(meData.get("role")).isEqualTo("customer");
    assertThat(meData).containsKeys("wallet_balance", "loyalty_points");

    ResponseEntity<Map> refreshed =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/refresh",
            jsonBody(Map.of("refresh_token", refresh)),
            Map.class);
    assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> refreshData =
        (Map<String, Object>) Objects.requireNonNull(refreshed.getBody()).get("data");
    String newRefresh = String.valueOf(refreshData.get("refresh_token"));
    assertThat(refreshData.get("access_token")).isInstanceOf(String.class);
    assertThat(newRefresh).isNotEqualTo(refresh);

    ResponseEntity<Map> reused =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/refresh",
            jsonBody(Map.of("refresh_token", refresh)),
            Map.class);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    @SuppressWarnings("unchecked")
    Map<String, Object> reuseErr =
        (Map<String, Object>) Objects.requireNonNull(reused.getBody()).get("error");
    assertThat(reuseErr.get("code")).isEqualTo("REFRESH_TOKEN_REUSED");

    UUID reusedUserId = UUID.fromString(String.valueOf(tokens.get("customer_id")));
    Integer unrevokedAfterReuse =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            reusedUserId);
    assertThat(unrevokedAfterReuse).isZero();
    Integer outboxEvents =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_message WHERE type = 'auth.refresh_token_reused'",
            Integer.class);
    assertThat(outboxEvents).isGreaterThanOrEqualTo(1);

    ResponseEntity<Map> successorBlocked =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/refresh",
            jsonBody(Map.of("refresh_token", newRefresh)),
            Map.class);
    assertThat(successorBlocked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // Re-login for further session ops (reuse revoked all).
    Map<String, Object> again = loginCustomer("+919999900021");
    String access2 = String.valueOf(again.get("access_token"));
    UUID userId = UUID.fromString(String.valueOf(again.get("customer_id")));

    // Seed two extra active sessions for logout-all AC.
    jdbc.update(
        "INSERT INTO sessions (id, user_id, user_type, refresh_token_hash, token_scope, ip_address,"
            + " last_active_at, expires_at) VALUES (?, ?, 'customer', ?, 'full', '127.0.0.1'::inet,"
            + " NOW(), NOW() + INTERVAL '30 days')",
        UUID.randomUUID(),
        userId,
        "hash-extra-1-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO sessions (id, user_id, user_type, refresh_token_hash, token_scope, ip_address,"
            + " last_active_at, expires_at) VALUES (?, ?, 'customer', ?, 'full', '127.0.0.1'::inet,"
            + " NOW(), NOW() + INTERVAL '30 days')",
        UUID.randomUUID(),
        userId,
        "hash-extra-2-" + UUID.randomUUID());

    ResponseEntity<Map> logoutAll =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/logout-all", bearer(access2, Map.of()), Map.class);
    assertThat(logoutAll.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> logoutData =
        (Map<String, Object>) Objects.requireNonNull(logoutAll.getBody()).get("data");
    assertThat(((Number) logoutData.get("sessions_revoked")).intValue()).isGreaterThanOrEqualTo(3);

    Integer stillActive =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL AND rotated_at"
                + " IS NULL",
            Integer.class,
            userId);
    assertThat(stillActive).isZero();

    // Ownership: user B cannot revoke user A's session.
    Map<String, Object> userA = loginCustomer("+919999900022");
    Map<String, Object> userB = loginCustomer("+919999900023");
    UUID aSession =
        jdbc.queryForObject(
            "SELECT id FROM sessions WHERE user_id = ? AND revoked_at IS NULL AND rotated_at IS NULL"
                + " LIMIT 1",
            UUID.class,
            UUID.fromString(String.valueOf(userA.get("customer_id"))));
    ResponseEntity<Map> forbidden =
        rest.exchange(
            baseUrl() + "/api/v1/auth/sessions/" + aSession,
            HttpMethod.DELETE,
            bearer(String.valueOf(userB.get("access_token")), null),
            Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> forbErr =
        (Map<String, Object>) Objects.requireNonNull(forbidden.getBody()).get("error");
    assertThat(forbErr.get("code")).isEqualTo("FORBIDDEN");

    ResponseEntity<Map> badSessionId =
        rest.exchange(
            baseUrl() + "/api/v1/auth/sessions/not-a-uuid",
            HttpMethod.DELETE,
            bearer(String.valueOf(userB.get("access_token")), null),
            Map.class);
    assertThat(badSessionId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> badIdErr =
        (Map<String, Object>) Objects.requireNonNull(badSessionId.getBody()).get("error");
    assertThat(badIdErr.get("code")).isEqualTo("VALIDATION_ERROR");

    // Expired refresh
    String expiredHash = "a".repeat(64);
    jdbc.update(
        "INSERT INTO sessions (id, user_id, user_type, refresh_token_hash, token_scope, ip_address,"
            + " last_active_at, expires_at) VALUES (?, ?, 'customer', ?, 'full', '127.0.0.1'::inet,"
            + " NOW() - INTERVAL '40 days', NOW() - INTERVAL '1 day')",
        UUID.randomUUID(),
        UUID.fromString(String.valueOf(userA.get("customer_id"))),
        expiredHash);
    // Cannot easily forge opaque token for that hash; exercise REFRESH_TOKEN_EXPIRED via service
    // path is covered in unit tests. Here assert list sessions works for user A.
    ResponseEntity<Map> sessions =
        rest.exchange(
            baseUrl() + "/api/v1/auth/sessions?page=1&limit=20",
            HttpMethod.GET,
            bearer(String.valueOf(userA.get("access_token")), null),
            Map.class);
    assertThat(sessions.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(Objects.requireNonNull(sessions.getBody()).get("data")).isInstanceOf(List.class);
  }

  private Map<String, Object> loginCustomer(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp",
            jsonBody(Map.of("phone", phone, "device_info", Map.of("platform", "android"))),
            Map.class);
    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData =
        (Map<String, Object>) Objects.requireNonNull(send.getBody()).get("data");
    String sessionId = String.valueOf(sendData.get("session_id"));

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            jsonBody(
                Map.of(
                    "session_id",
                    sessionId,
                    "phone",
                    phone,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-device")),
            Map.class);
    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    @SuppressWarnings("unchecked")
    Map<String, Object> customer = (Map<String, Object>) data.get("customer");
    return Map.of(
        "access_token",
        data.get("access_token"),
        "refresh_token",
        data.get("refresh_token"),
        "customer_id",
        customer.get("id"));
  }

  private HttpEntity<Map<String, Object>> bearer(String accessToken, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(accessToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
