package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.api.support.PrescriptionFixtures;
import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.order.adapter.out.persistence.StubPrescriptionAdapter;
import com.nammamedmate.order.application.RxQuoteBroadcastService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

class RxQuoteIT extends AbstractApiIT {

  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0012-0012-0012-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0012-0012-0012-000000000002");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0012-0012-0012-000000000001");
  private static final UUID STAFF2 = UUID.fromString("bbbbbbbb-0012-0012-0012-000000000002");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OWNER1_EMAIL = "rxquote-owner1@test.in";
  private static final String OWNER2_EMAIL = "rxquote-owner2@test.in";
  private static final String PASSWORD = "RxQuoteTest1!";
  private static final String CUSTOMER_PHONE = "+919999900052";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RxQuoteBroadcastService rxQuoteService;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM rx_broadcast_pharmacies WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update(
        "DELETE FROM rx_broadcasts WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM customer_addresses WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id IN (?, ?)", STAFF1, STAFF2);
    jdbc.update(
        "DELETE FROM pharmacy_staff WHERE id IN (?, ?) OR email IN (?, ?)",
        STAFF1,
        STAFF2,
        OWNER1_EMAIL,
        OWNER2_EMAIL);
    jdbc.update(
        "DELETE FROM pharmacies WHERE code IN ('PHM-RX1', 'PHM-RX2') OR id IN (?, ?)", PH1, PH2);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    insertPharmacy(PH1, "Rx Sai", "PHM-RX1", 15.0000, 75.0000);
    insertPharmacy(PH2, "Rx Apollo", "PHM-RX2", 15.0020, 75.0020);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.20, 80, 80.00, NOW(), NOW())",
        PH2);

    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Rx Owner1', ?, ?,"
            + " 'ACTIVE', 0, NOW(), NOW())",
        STAFF1,
        OWNER1_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF1,
        PH1,
        OWNER_ROLE_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Rx Owner2', ?, ?,"
            + " 'ACTIVE', 0, NOW(), NOW())",
        STAFF2,
        OWNER2_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF2,
        PH2,
        OWNER_ROLE_ID);

    String customerToken = customerLogin(CUSTOMER_PHONE);
    customerId =
        jdbc.queryForObject("SELECT id FROM customers WHERE phone = ?", UUID.class, CUSTOMER_PHONE);
    addressId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO customer_addresses (
          id, customer_id, label, flat_building, area_locality, city, state, pincode,
          latitude, longitude, is_default, created_at, updated_at
        ) VALUES (?, ?, 'Home', '12', 'Koramangala', 'Bengaluru', 'KA', '560034',
          15.0005, 75.0005, true, NOW(), NOW())
        """,
        addressId,
        customerId);
    jdbc.update("UPDATE customers SET default_address_id = ? WHERE id = ?", addressId, customerId);
    // keep token used below via login again in test
    assertThat(customerToken).isNotBlank();
  }

  @Test
  void broadcastQuoteSelectAndExpiryJobs() {
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String ph1Token = pharmacyLogin(OWNER1_EMAIL);
    String ph2Token = pharmacyLogin(OWNER2_EMAIL);

    // prior ACTIVE cart should be abandoned on select
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.GET, bearer(customerToken, null), Map.class);

    UUID rxId = PrescriptionFixtures.insertVerified(jdbc, customerId);
    ResponseEntity<Map> broadcast =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/broadcast",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of(
                    "prescription_id",
                    rxId.toString(),
                    "delivery_address_id",
                    addressId.toString(),
                    "patient_name",
                    "Ravi Kumar",
                    "notes",
                    "quote all")),
            Map.class);
    assertThat(broadcast.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> bcData = data(broadcast);
    // Shared IT DB may retain other nearby pharmacies; require our two + cap ≤10.
    assertThat(((Number) bcData.get("pharmacies_notified")).intValue()).isBetween(2, 10);
    assertThat(bcData.get("can_view_quotes")).isEqualTo(false);
    String broadcastId = String.valueOf(bcData.get("broadcast_id"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notified = (List<Map<String, Object>>) bcData.get("pharmacies");
    assertThat(notified.stream().map(p -> String.valueOf(p.get("pharmacy_id"))))
        .contains(PH1.toString(), PH2.toString());

    PrescriptionFixtures.insertExpired(jdbc, customerId, StubPrescriptionAdapter.EXPIRED_ID);
    ResponseEntity<Map> expiredRx =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/broadcast",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of(
                    "prescription_id",
                    StubPrescriptionAdapter.EXPIRED_ID.toString(),
                    "delivery_address_id",
                    addressId.toString(),
                    "patient_name",
                    "Ravi")),
            Map.class);
    assertThat(expiredRx.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(expiredRx)).isEqualTo("PRESCRIPTION_EXPIRED");

    ResponseEntity<Map> incoming =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/rx-quotes",
            HttpMethod.GET,
            bearer(ph1Token, null),
            Map.class);
    assertThat(incoming.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> incomingData =
        (List<Map<String, Object>>) Objects.requireNonNull(incoming.getBody()).get("data");
    assertThat(incomingData).isNotEmpty();
    assertThat(incomingData.getFirst().get("medicines_requested")).isInstanceOf(List.class);

    ResponseEntity<Map> quote1 =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/rx-quotes/" + broadcastId + "/quote",
            HttpMethod.POST,
            bearer(
                ph1Token,
                Map.of(
                    "medicines_available",
                    List.of(
                        Map.of(
                            "name",
                            "Metformin 500mg",
                            "qty",
                            60,
                            "price",
                            255.00,
                            "product_id",
                            UUID.randomUUID().toString()),
                        Map.of(
                            "name",
                            "Glipizide 5mg",
                            "qty",
                            30,
                            "price",
                            85.50,
                            "product_id",
                            UUID.randomUUID().toString())),
                    "delivery_eta_minutes",
                    22)),
            Map.class);
    assertThat(quote1.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(quote1).get("status")).isEqualTo("QUOTED");

    ResponseEntity<Map> quote2 =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/rx-quotes/" + broadcastId + "/quote",
            HttpMethod.POST,
            bearer(
                ph2Token,
                Map.of(
                    "medicines_available",
                    List.of(
                        Map.of(
                            "name",
                            "Metformin 500mg",
                            "qty",
                            60,
                            "price",
                            270.00,
                            "product_id",
                            UUID.randomUUID().toString())),
                    "delivery_eta_minutes",
                    18)),
            Map.class);
    assertThat(quote2.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> status =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/" + broadcastId,
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(status).get("quotes_received")).isEqualTo(2);
    assertThat(data(status).get("can_view_quotes")).isEqualTo(true);

    ResponseEntity<Map> quotes =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/" + broadcastId + "/quotes",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(quotes.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> quoteList =
        (List<Map<String, Object>>) Objects.requireNonNull(quotes.getBody()).get("data");
    assertThat(quoteList).hasSize(2);
    boolean hasFastest =
        quoteList.stream()
            .anyMatch(
                q -> {
                  @SuppressWarnings("unchecked")
                  List<String> tags = (List<String>) q.get("tags");
                  return tags != null && tags.contains("FASTEST");
                });
    boolean hasLowest =
        quoteList.stream()
            .anyMatch(
                q -> {
                  @SuppressWarnings("unchecked")
                  List<String> tags = (List<String>) q.get("tags");
                  return tags != null && tags.contains("LOWEST_PRICE");
                });
    assertThat(hasFastest).isTrue();
    assertThat(hasLowest).isTrue();

    ResponseEntity<Map> selected =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/" + broadcastId + "/select",
            HttpMethod.POST,
            bearer(customerToken, Map.of("pharmacy_id", PH1.toString())),
            Map.class);
    assertThat(selected.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> sel = data(selected);
    assertThat(sel.get("status")).isEqualTo("SELECTED");
    assertThat(sel.get("cart_id")).isNotNull();

    Integer abandoned =
        jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM carts WHERE customer_id = ? AND status = 'ABANDONED'",
            Integer.class,
            customerId);
    assertThat(abandoned).isGreaterThanOrEqualTo(1);
    Integer active =
        jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM carts WHERE customer_id = ? AND status = 'ACTIVE'",
            Integer.class,
            customerId);
    assertThat(active).isEqualTo(1);

    // expiry jobs (no-op on selected broadcast; exercise pharmacy slot expiry path)
    assertThat(rxQuoteService.expirePharmacyResponseWindows()).isGreaterThanOrEqualTo(0);
    assertThat(rxQuoteService.expireBroadcastsAndNotify()).isGreaterThanOrEqualTo(0);

    // force-expire a fresh broadcast for push outbox
    UUID rx2 = PrescriptionFixtures.insertVerified(jdbc, customerId);
    ResponseEntity<Map> bc2 =
        rest.exchange(
            baseUrl() + "/api/v1/orders/rx-quote/broadcast",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of(
                    "prescription_id",
                    rx2.toString(),
                    "delivery_address_id",
                    addressId.toString(),
                    "patient_name",
                    "Ravi")),
            Map.class);
    assertThat(bc2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String bc2Id = String.valueOf(data(bc2).get("broadcast_id"));
    jdbc.update(
        "UPDATE rx_broadcasts SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
        bc2Id);
    jdbc.update(
        "UPDATE rx_broadcast_pharmacies SET response_deadline = NOW() - INTERVAL '1 minute'"
            + " WHERE broadcast_id = ?::uuid AND status = 'NOTIFIED'",
        bc2Id);
    assertThat(rxQuoteService.expirePharmacyResponseWindows()).isGreaterThanOrEqualTo(1);
    assertThat(rxQuoteService.expireBroadcastsAndNotify()).isGreaterThanOrEqualTo(1);
    Integer outbox =
        jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM outbox_message WHERE type = 'customer.notification.requested'",
            Integer.class);
    assertThat(outbox).isGreaterThanOrEqualTo(1);
  }

  private void insertPharmacy(UUID id, String name, String code, double lat, double lng) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', NOW(), NOW())",
        id,
        name,
        name,
        code,
        lat,
        lng,
        "{\"flat\":\"1\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}");
  }

  private String pharmacyLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/login",
            json(Map.of("identifier", email, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(login).get("access_token"));
  }

  private String customerLogin(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp", json(Map.of("phone", phone)), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData =
        (Map<String, Object>) Objects.requireNonNull(send.getBody()).get("data");
    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            json(
                Map.of(
                    "session_id",
                    sendData.get("session_id"),
                    "phone",
                    phone,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-rx-quote")),
            Map.class);
    return String.valueOf(data(verify).get("access_token"));
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
    return (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<String, Object> err =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    return String.valueOf(err.get("code"));
  }
}
