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

class CustomerAddressIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900011";

  @Autowired private TestRestTemplate rest;

  @Test
  void addressCrudGeocodeAndValidation() {
    String token = verifyCustomer(MAGIC_PHONE);

    ResponseEntity<Map> create =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses",
            HttpMethod.POST,
            bearer(token, validAddress("HOME", true)),
            Map.class);
    assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> created = data(create);
    assertThat(created.get("is_default")).isEqualTo(true);
    String addressId = String.valueOf(created.get("id"));

    ResponseEntity<Map> list =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dataList(list)).isNotEmpty();

    ResponseEntity<Map> badPincode =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses/" + addressId,
            HttpMethod.PUT,
            bearer(
                token,
                Map.of(
                    "label",
                    "HOME",
                    "flat_building",
                    "Flat 1",
                    "area_locality",
                    "Area",
                    "city",
                    "Bengaluru",
                    "state",
                    "Karnataka",
                    "pincode",
                    "56006",
                    "latitude",
                    12.97,
                    "longitude",
                    77.59)),
            Map.class);
    assertThat(badPincode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(badPincode)).isEqualTo("VALIDATION_ERROR");

    ResponseEntity<Map> geocode =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses/geocode",
            HttpMethod.POST,
            bearer(token, Map.of("latitude", 12.9716, "longitude", 77.5946)),
            Map.class);
    assertThat(geocode.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> suggested = (Map<String, Object>) data(geocode).get("suggested_address");
    assertThat(suggested.get("city")).isEqualTo("Bengaluru");
    assertThat(suggested.get("pincode")).isNotNull();

    ResponseEntity<Map> otherPhone =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses/" + addressId,
            HttpMethod.DELETE,
            bearer(verifyCustomer("+919999900012"), null),
            Map.class);
    assertThat(otherPhone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(errorCode(otherPhone)).isEqualTo("ADDRESS_NOT_FOUND");

    ResponseEntity<Map> delete =
        rest.exchange(
            baseUrl() + "/api/v1/customers/me/addresses/" + addressId,
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
                    "it-address-" + phone)),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    return String.valueOf(verifyData.get("access_token"));
  }

  private static Map<String, Object> validAddress(String label, boolean isDefault) {
    return Map.of(
        "label",
        label,
        "flat_building",
        "Flat 4B",
        "area_locality",
        "Whitefield",
        "city",
        "Bengaluru",
        "state",
        "Karnataka",
        "pincode",
        "560066",
        "latitude",
        12.9693,
        "longitude",
        77.7499,
        "is_default",
        isDefault);
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
  private static List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    return (List<Map<String, Object>>) body.get("data");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }
}
