package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

class CustomerLoyaltyIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900050";

  @Autowired private TestRestTemplate rest;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void resetRateLimits() {
    flushRedis("otp:*");
    flushRedis("customer:loyalty:*");
  }

  @Test
  void loyaltyStatusTransactionsAndErrors() {
    String token = verifyCustomer(MAGIC_PHONE, "it-loyalty");

    ResponseEntity<Map> status =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/loyalty", HttpMethod.GET, bearer(token), Map.class);
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = data(status);
    assertThat(data.get("tier")).isEqualTo("NONE");
    assertThat(data.get("points_balance")).isEqualTo(0);
    assertThat(data.get("points_earned_lifetime")).isEqualTo(0);
    assertThat(data.get("tier_progress")).isInstanceOf(Map.class);
    assertThat(data.get("tier_thresholds")).isInstanceOf(Map.class);

    ResponseEntity<Map> txs =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/loyalty/transactions?page=1&limit=20&order=desc",
            HttpMethod.GET,
            bearer(token),
            Map.class);
    assertThat(txs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dataList(txs)).isEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> meta =
        (Map<String, Object>) Objects.requireNonNull(txs.getBody()).get("meta");
    assertThat(meta).containsKeys("page", "limit", "total", "has_next");

    ResponseEntity<Map> badType =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/loyalty/transactions?type=NOPE",
            HttpMethod.GET,
            bearer(token),
            Map.class);
    assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(badType)).isEqualTo("VALIDATION_ERROR");

    ResponseEntity<Map> unauthorized =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/loyalty",
            HttpMethod.GET,
            HttpEntity.EMPTY,
            Map.class);
    assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(errorCode(unauthorized)).isEqualTo("UNAUTHORIZED");
  }

  private String verifyCustomer(String phone, String device) {
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
                    device)),
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

  private static HttpEntity<?> bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
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
}
