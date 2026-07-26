package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.math.BigDecimal;
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

class CustomerReferralIT extends AbstractApiIT {

  private static final String REFERRER_PHONE = "+919999900051";
  private static final String REFEREE_PHONE = "+919999900052";
  private static final String SELF_PHONE = "+919999900053";
  private static final String UNKNOWN_PHONE = "+919999900054";

  @Autowired private TestRestTemplate rest;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void resetRateLimits() {
    flushRedis("otp:*");
    flushRedis("customer:referral:*");
  }

  @Test
  void applyReferralHappyPathAndErrors() {
    String referrerToken = verifyCustomer(REFERRER_PHONE, "it-ref-referrer");
    String referrerCode = String.valueOf(getReferral(referrerToken).get("referral_code"));
    assertThat(referrerCode).hasSize(7);

    String refereeToken = verifyCustomer(REFEREE_PHONE, "it-ref-referee");
    Map<String, Object> refereeInfo = getReferral(refereeToken);
    assertThat(refereeInfo.get("referral_link").toString()).contains(referralCode(refereeInfo));

    ResponseEntity<Map> applied = apply(refereeToken, referrerCode);
    assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> appliedData = data(applied);
    assertThat(appliedData.get("status")).isEqualTo("PENDING");
    assertThat(appliedData.get("referral_event_id")).isNotNull();
    assertThat(new BigDecimal(String.valueOf(appliedData.get("reward_amount"))))
        .isEqualByComparingTo("100.00");

    ResponseEntity<Map> reused = apply(refereeToken, referrerCode);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(reused)).isEqualTo("REFERRAL_ALREADY_USED");

    String selfToken = verifyCustomer(SELF_PHONE, "it-ref-self");
    String selfCode = String.valueOf(getReferral(selfToken).get("referral_code"));
    ResponseEntity<Map> self = apply(selfToken, selfCode);
    assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(self)).isEqualTo("SELF_REFERRAL_NOT_ALLOWED");

    String unknownToken = verifyCustomer(UNKNOWN_PHONE, "it-ref-unknown");
    ResponseEntity<Map> badFormat = apply(unknownToken, "SHORT");
    assertThat(badFormat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(badFormat)).isEqualTo("VALIDATION_ERROR");

    ResponseEntity<Map> notFound = apply(unknownToken, "ZZZ9999");
    assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(errorCode(notFound)).isEqualTo("REFERRAL_CODE_NOT_FOUND");

    ResponseEntity<Map> unauthorized =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/referral",
            HttpMethod.GET,
            HttpEntity.EMPTY,
            Map.class);
    assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(errorCode(unauthorized)).isEqualTo("UNAUTHORIZED");
  }

  private Map<String, Object> getReferral(String token) {
    ResponseEntity<Map> response =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/referral", HttpMethod.GET, bearer(token), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return data(response);
  }

  private ResponseEntity<Map> apply(String token, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        baseUrl() + "/api/v1/customers/me/referral/apply",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("referrer_code", code), headers),
        Map.class);
  }

  private static String referralCode(Map<String, Object> info) {
    return String.valueOf(info.get("referral_code"));
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
  private static String errorCode(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }
}
