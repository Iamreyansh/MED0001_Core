package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class CustomerPaymentMethodIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900021";

  @Autowired private TestRestTemplate rest;

  @Test
  void paymentMethodCrudAndMasking() {
    String token = verifyCustomer(MAGIC_PHONE);

    ResponseEntity<Map> upi =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/upi",
            HttpMethod.POST,
            bearer(token, Map.of("upi_id", "ramesh@okaxis", "nickname", "GPay")),
            Map.class);
    assertThat(upi.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> upiData = data(upi);
    assertThat(upiData.get("upi_handle")).isEqualTo("***@okaxis");
    assertThat(upiData).doesNotContainKey("upi_id");
    String upiId = String.valueOf(upiData.get("id"));

    ResponseEntity<Map> card =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/card",
            HttpMethod.POST,
            bearer(
                token,
                Map.of(
                    "gateway_token_id",
                    "token_itcard1",
                    "card_last4",
                    "4242",
                    "card_network",
                    "VISA",
                    "card_type",
                    "CREDIT",
                    "nickname",
                    "Axis")),
            Map.class);
    assertThat(card.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> cardData = data(card);
    assertThat(cardData.get("card_last4")).isEqualTo("4242");
    assertThat(cardData).doesNotContainKey("gateway_token_id");
    String cardId = String.valueOf(cardData.get("id"));

    ResponseEntity<Map> missingLast4 =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/card",
            HttpMethod.POST,
            bearer(
                token,
                Map.of(
                    "gateway_token_id",
                    "token_nolast4",
                    "card_network",
                    "VISA",
                    "card_type",
                    "CREDIT")),
            Map.class);
    assertThat(missingLast4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(missingLast4)).isEqualTo("VALIDATION_ERROR");

    ResponseEntity<Map> list =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> listed = data(list);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upiList = (List<Map<String, Object>>) listed.get("upi");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cards = (List<Map<String, Object>>) listed.get("cards");
    assertThat(upiList).isNotEmpty();
    assertThat(upiList.get(0)).doesNotContainKey("upi_id");
    assertThat(cards.get(0)).doesNotContainKey("gateway_token_id");

    ResponseEntity<Map> setDefault =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/" + cardId + "/set-default",
            HttpMethod.PATCH,
            bearer(token, null),
            Map.class);
    assertThat(setDefault.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(setDefault).get("is_default")).isEqualTo(true);
    assertThat(data(setDefault).get("previous_default_id")).isEqualTo(null);

    ResponseEntity<Map> switchDefault =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/" + upiId + "/set-default",
            HttpMethod.PATCH,
            bearer(token, null),
            Map.class);
    assertThat(switchDefault.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(switchDefault).get("previous_default_id")).isEqualTo(cardId);

    ResponseEntity<Map> invalidVpa =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/upi",
            HttpMethod.POST,
            bearer(token, Map.of("upi_id", "nobody@invalid")),
            Map.class);
    assertThat(invalidVpa.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(invalidVpa)).isEqualTo("INVALID_UPI_VPA");

    ResponseEntity<Map> delete =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/payment-methods/" + cardId,
            HttpMethod.DELETE,
            bearer(token, null),
            Map.class);
    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.OK);
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
                    "it-pm-" + phone)),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    return String.valueOf(verifyData.get("access_token"));
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
  private static Map<String, Object> data(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    return (Map<String, Object>) body.get("data");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }
}
