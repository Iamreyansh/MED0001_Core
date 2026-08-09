package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CustomerWalletIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900022";
  private static final UUID FINANCE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001");
  private static final UUID SUPPORT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0002");
  private static final String ADMIN_PASSWORD = "WalletAdmin1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void seedAdmins() {
    flushRedis("otp:*");
    flushRedis("customer:wallet:*");
    flushRedis("admin:wallet:*");
    flushRedis("admin:ip:*");
    flushRedis("admin:user:*");
    seedAdmin(FINANCE_ID, "finance-wallet@test.in", "admin_finance");
    seedAdmin(SUPPORT_ID, "support-wallet@test.in", "admin_support");
  }

  @Test
  void walletBalanceCreditTransactionsAndForbidden() {
    String token = verifyCustomer(MAGIC_PHONE);

    ResponseEntity<Map> balance =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/wallet",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> wallet = data(balance);
    assertThat(asDecimal(wallet.get("balance"))).isEqualByComparingTo("0.00");
    assertThat(asDecimal(wallet.get("lifetime_credited"))).isEqualByComparingTo("0.00");
    assertThat(asDecimal(wallet.get("lifetime_debited"))).isEqualByComparingTo("0.00");

    ResponseEntity<Map> me =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me", HttpMethod.GET, bearer(token, null), Map.class);
    String customerId = String.valueOf(data(me).get("id"));

    String financeToken = adminLogin("finance-wallet@test.in");
    ResponseEntity<Map> credit =
        rest.exchange(
            baseUrl() + "/api/v1/admin/customers/" + customerId + "/wallet/credit",
            HttpMethod.POST,
            bearer(
                financeToken,
                Map.of(
                    "amount",
                    100,
                    "reason",
                    "GOODWILL",
                    "note",
                    "Apology credit",
                    "reference_id",
                    "case-1"),
                "wallet-it-credit-1"),
            Map.class);
    assertThat(credit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> credited = data(credit);
    assertThat(asDecimal(credited.get("amount_credited"))).isEqualByComparingTo("100.00");
    assertThat(asDecimal(credited.get("new_balance"))).isEqualByComparingTo("100.00");
    assertThat(credited.get("expires_at")).isNotNull();

    ResponseEntity<Map> replay =
        rest.exchange(
            baseUrl() + "/api/v1/admin/customers/" + customerId + "/wallet/credit",
            HttpMethod.POST,
            bearer(
                financeToken,
                Map.of(
                    "amount",
                    100,
                    "reason",
                    "GOODWILL",
                    "note",
                    "Apology credit",
                    "reference_id",
                    "case-1"),
                "wallet-it-credit-1"),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(data(replay).get("transaction_id")).isEqualTo(credited.get("transaction_id"));

    ResponseEntity<Map> overLimit =
        rest.exchange(
            baseUrl() + "/api/v1/admin/customers/" + customerId + "/wallet/credit",
            HttpMethod.POST,
            bearer(
                financeToken,
                Map.of("amount", 1500, "reason", "GOODWILL", "note", "too much"),
                "wallet-it-over-limit"),
            Map.class);
    assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(overLimit)).isEqualTo("ADMIN_CREDIT_EXCEEDS_LIMIT");

    String supportToken = adminLogin("support-wallet@test.in");
    ResponseEntity<Map> forbidden =
        rest.exchange(
            baseUrl() + "/api/v1/admin/customers/" + customerId + "/wallet/credit",
            HttpMethod.POST,
            bearer(
                supportToken,
                Map.of("amount", 10, "reason", "GOODWILL", "note", "no finance"),
                "wallet-it-support"),
            Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(errorCode(forbidden)).isEqualTo("INSUFFICIENT_PERMISSIONS");

    ResponseEntity<Map> txs =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/wallet/transactions?type=CREDIT",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(txs.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<?> items = dataList(txs);
    assertThat(items).isNotEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) items.getFirst();
    assertThat(first.get("type")).isEqualTo("CREDIT");
  }

  private void seedAdmin(UUID id, String email, String role) {
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", id);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", id);
    jdbc.update("DELETE FROM admin_staff WHERE id = ?", id);
    String hash = new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE',"
            + " false, 0, NOW(), NOW())",
        id,
        "Wallet Admin",
        email,
        hash,
        role);
  }

  private String verifyCustomer(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp", json(Map.of("phone", phone)), Map.class);
    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
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
                    "it-wallet")),
            Map.class);
    assertThat(verify.getStatusCode())
        .as("verify-otp body=%s", verify.getBody())
        .isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    return String.valueOf(verifyData.get("access_token"));
  }

  private void flushRedis(String pattern) {
    var keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
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
    return bearer(token, body, null);
  }

  private static HttpEntity<?> bearer(String token, Map<String, ?> body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
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
  private static Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
  }

  @SuppressWarnings("unchecked")
  private static List<?> dataList(ResponseEntity<Map> response) {
    return (List<?>) Objects.requireNonNull(response.getBody()).get("data");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }

  private static BigDecimal asDecimal(Object value) {
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    return new BigDecimal(String.valueOf(value));
  }
}
