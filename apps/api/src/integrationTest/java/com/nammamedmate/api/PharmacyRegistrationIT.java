package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

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

class PharmacyRegistrationIT extends AbstractApiIT {

  @Autowired private TestRestTemplate rest;

  @Test
  void registerVerifyAndStatusHappyPath() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String email = "owner-" + suffix + "@nammamedmate.test";
    String phone = "+9198" + String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000));

    ResponseEntity<Map> register =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(
                registrationBody(email, phone, "29AABPP1234F1ZZ", "AABPP1234F", "DL-" + suffix)),
            Map.class);

    assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<?, ?> regBody = Objects.requireNonNull(register.getBody());
    assertThat(regBody.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> regData = (Map<String, Object>) regBody.get("data");
    assertThat(regData.get("status")).isEqualTo("PENDING_KYC");
    assertThat(regData.get("plan")).isEqualTo("FREE");
    String pharmacyId = String.valueOf(regData.get("pharmacy_id"));

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register/verify-email",
            jsonBody(Map.of("email", email, "otp", "123456")),
            Map.class);
    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    String accessToken = String.valueOf(verifyData.get("access_token"));
    assertThat(accessToken).isNotBlank();
    assertThat(verifyData.get("pharmacy_id")).isEqualTo(pharmacyId);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(accessToken);
    ResponseEntity<Map> status =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/registration-status",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> statusData =
        (Map<String, Object>) Objects.requireNonNull(status.getBody()).get("data");
    assertThat(statusData.get("email_verified")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) statusData.get("kyc");
    assertThat(kyc.get("documents_required")).isEqualTo(5);
    assertThat(statusData.get("profile_completeness_pct")).isEqualTo(45);
  }

  @Test
  void registerRejectsInvalidGstinAndPhone() {
    ResponseEntity<Map> badGstin =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(
                registrationBody(
                    "bad-gstin@nammamedmate.test",
                    "+919811122233",
                    "29AABPP1234F1ZA",
                    "AABPP1234F",
                    "DL-BAD-GSTIN")),
            Map.class);
    assertThat(badGstin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> err =
        (Map<String, Object>) Objects.requireNonNull(badGstin.getBody()).get("error");
    assertThat(err.get("code")).isEqualTo("INVALID_GSTIN");

    ResponseEntity<Map> badPhone =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(
                registrationBody(
                    "bad-phone@nammamedmate.test",
                    "+911811122233",
                    "29AABPP1234F2ZY",
                    "AABPP1234F",
                    "DL-BAD-PHONE")),
            Map.class);
    assertThat(badPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> phoneErr =
        (Map<String, Object>) Objects.requireNonNull(badPhone.getBody()).get("error");
    assertThat(phoneErr.get("code")).isEqualTo("INVALID_PHONE");
  }

  private static Map<String, Object> registrationBody(
      String email, String phone, String gstin, String pan, String licence) {
    return Map.ofEntries(
        Map.entry("owner_name", "Priya Sharma"),
        Map.entry("business_name", "Sharma Medical Store"),
        Map.entry("phone", phone),
        Map.entry("email", email),
        Map.entry("password", "Passw0rd!"),
        Map.entry("business_type", "PHARMACY"),
        Map.entry(
            "address",
            Map.of(
                "flat", "12",
                "area", "MG Road",
                "city", "Bengaluru",
                "state", "Karnataka",
                "pincode", "560001")),
        Map.entry("gstin", gstin),
        Map.entry("drug_licence_number", licence),
        Map.entry("pan_number", pan));
  }

  private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
