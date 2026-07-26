package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CustomerProfileIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900010";
  private static final String ADMIN_PASSWORD = "Passw0rd!";
  private static final UUID SUPPORT_ID = UUID.fromString("cccccccc-0002-0001-0001-000000000001");

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void customerMeAndAdminListHappyPath() {
    String customerToken = verifyCustomer(MAGIC_PHONE);

    ResponseEntity<Map> me =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> meBody = Objects.requireNonNull(me.getBody());
    assertThat(meBody.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> meData = (Map<String, Object>) meBody.get("data");
    assertThat(meData.get("phone")).isEqualTo(MAGIC_PHONE);
    assertThat(meData.get("segment")).isEqualTo("NEW");
    assertThat(meData).containsKeys("wallet_balance", "loyalty_points", "loyalty_tier");

    ResponseEntity<Map> patch =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me",
            HttpMethod.PATCH,
            bearer(customerToken, Map.of("name", "IT Customer", "preferred_language", "kn")),
            Map.class);
    assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> badLang =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me",
            HttpMethod.PATCH,
            bearer(customerToken, Map.of("preferred_language", "de")),
            Map.class);
    assertThat(badLang.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(badLang)).isEqualTo("VALIDATION_ERROR");

    seedSupportAdmin();
    String adminToken = adminLogin("support-cust@test.in");

    ResponseEntity<Map> list =
        rest.exchange(
            baseUrl() + "/api/v1/admin/customers?segment=NEW&is_flagged=false&limit=5",
            HttpMethod.GET,
            bearer(adminToken, null),
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> listBody = Objects.requireNonNull(list.getBody());
    assertThat(listBody.get("success")).isEqualTo(true);
    assertThat(listBody.get("data")).isInstanceOf(java.util.List.class);
  }

  private String verifyCustomer(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp", json(Map.of("phone", phone)), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData =
        (Map<String, Object>) Objects.requireNonNull(send.getBody()).get("data");
    String sessionId = String.valueOf(sendData.get("session_id"));
    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            json(
                Map.of(
                    "session_id",
                    sessionId,
                    "phone",
                    phone,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-profile")),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    return String.valueOf(verifyData.get("access_token"));
  }

  private void seedSupportAdmin() {
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", SUPPORT_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", SUPPORT_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ?", SUPPORT_ID);
    String hash = new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Support Cust',"
            + " 'support-cust@test.in', ?, 'admin_support', 'ACTIVE', false, 0, NOW(), NOW())",
        SUPPORT_ID,
        hash);
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", email, "password", ADMIN_PASSWORD)),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    return String.valueOf(data.get("access_token"));
  }

  private static HttpEntity<?> bearer(String token, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
      return new HttpEntity<>(body, headers);
    }
    return new HttpEntity<>(headers);
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }
}
