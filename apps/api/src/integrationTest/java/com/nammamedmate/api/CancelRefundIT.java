package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import java.util.HashMap;
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

class CancelRefundIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0106");
  private static final UUID FIN_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0107");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0015-0015-0015-000000000001");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0015-0015-0015-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "cancel-ops@test.in";
  private static final String FIN_EMAIL = "cancel-fin@test.in";
  private static final String OWNER_EMAIL = "cancel-owner@test.in";
  private static final String PASSWORD = "CancelRf1!";
  private static final String CUSTOMER_PHONE = "+919999900055";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrderLifecycleService lifecycle;
  @Autowired private RazorpayPaymentPort razorpay;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM refund WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN (SELECT"
            + " id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM order_cancellation WHERE order_id IN (SELECT id FROM orders WHERE customer_id"
            + " IN (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM order_status_event WHERE order_id IN (SELECT id FROM orders WHERE customer_id"
            + " IN (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM customer_addresses WHERE customer_id IN (SELECT id FROM customers WHERE phone ="
            + " ?)",
        CUSTOMER_PHONE);
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id IN (?, ?)", OPS_ID, FIN_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id IN (?, ?)", OPS_ID, FIN_ID);
    jdbc.update(
        "DELETE FROM admin_staff WHERE id IN (?, ?) OR email IN (?, ?)",
        OPS_ID,
        FIN_ID,
        OPS_EMAIL,
        FIN_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'CancelIT%'");
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PH1);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cancel Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cancel Fin', ?,"
            + " ?, 'admin_finance', 'ACTIVE', false, 0, NOW(), NOW())",
        FIN_ID,
        FIN_EMAIL,
        hash);
    insertPharmacy(PH1, "Cancel Sai", "PHM-CR6", 12.9350, 77.6130);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cancel Owner', ?, ?,"
            + " 'ACTIVE', 0, NOW(), NOW())",
        STAFF1,
        OWNER_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF1,
        PH1,
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
          12.9345, 77.6125, true, NOW(), NOW())
        """,
        addressId,
        customerId);
    jdbc.update("UPDATE customers SET default_address_id = ? WHERE id = ?", addressId, customerId);
    assertThat(customerToken).isNotBlank();
  }

  @Test
  void cancellationAndRefundAcceptanceCriteria() {
    String opsToken = adminLogin(OPS_EMAIL);
    String finToken = adminLogin(FIN_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();
    String med = createMedicine(opsToken, "CancelIT Para", "OTC", 85.00);
    mapMedicine(pharmacyToken, med, 85.00, 200);

    // AC1: customer cannot cancel PACKING
    String packingOrder = placeCod(customerToken, med);
    rest.exchange(
        baseUrl() + "/api/v1/pharmacy/orders/" + packingOrder + "/accept",
        HttpMethod.POST,
        bearer(pharmacyToken, null),
        Map.class);
    rest.exchange(
        baseUrl() + "/api/v1/pharmacy/orders/" + packingOrder + "/status",
        HttpMethod.PATCH,
        bearer(pharmacyToken, Map.of("status", "PACKING")),
        Map.class);
    ResponseEntity<Map> cannotCancel =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + packingOrder + "/cancel",
            HttpMethod.POST,
            bearer(customerToken, Map.of("reason", "CHANGED_MIND")),
            Map.class);
    assertThat(cannotCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(cannotCancel)).isEqualTo("ORDER_CANNOT_CANCEL");

    // AC2: customer cancel UPI → SOURCE refund + razorpay call
    String upiOrderId = placeAndConfirmUpi(customerToken, med);
    ResponseEntity<Map> custCancel =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + upiOrderId + "/cancel",
            HttpMethod.POST,
            bearer(customerToken, Map.of("reason", "CHANGED_MIND")),
            Map.class);
    assertThat(custCancel.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> refundView = (Map<String, Object>) data(custCancel).get("refund");
    assertThat(refundView.get("initiated")).isEqualTo(true);
    assertThat(refundView.get("refund_to")).isEqualTo("SOURCE");
    Integer refundCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM refund WHERE order_id = ?::uuid AND refund_to = 'SOURCE'",
            Integer.class,
            upiOrderId);
    assertThat(refundCount).isEqualTo(1);
    String rzRefundId =
        jdbc.queryForObject(
            "SELECT razorpay_refund_id FROM refund WHERE order_id = ?::uuid LIMIT 1",
            String.class,
            upiOrderId);
    assertThat(rzRefundId).startsWith("rfnd_stub_");

    // AC8: cancel notifications
    Integer notifyCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_message WHERE type = 'customer.notification.requested'"
                + " AND payload_json LIKE ?",
            Integer.class,
            "%" + upiOrderId + "%");
    assertThat(notifyCount).isGreaterThanOrEqualTo(2);

    // AC3: admin cancel COD → no refund initiated
    String codId = placeCod(customerToken, med);
    ResponseEntity<Map> adminCodCancel =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + codId + "/cancel",
            HttpMethod.POST,
            bearer(
                opsToken,
                Map.of("reason", "ops cod cancel", "refund_amount", 0, "refund_to", "WALLET")),
            Map.class);
    assertThat(adminCodCancel.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> codRefund = (Map<String, Object>) data(adminCodCancel).get("refund");
    assertThat(codRefund.get("initiated")).isEqualTo(false);

    // AC7: admin cannot cancel DELIVERED
    String deliveredId = placeAndConfirmUpi(customerToken, med);
    rest.exchange(
        baseUrl() + "/api/v1/pharmacy/orders/" + deliveredId + "/accept",
        HttpMethod.POST,
        bearer(pharmacyToken, null),
        Map.class);
    rest.exchange(
        baseUrl() + "/api/v1/admin/orders/" + deliveredId + "/status",
        HttpMethod.PATCH,
        bearer(opsToken, Map.of("status", "DELIVERED", "reason", "force deliver")),
        Map.class);
    ResponseEntity<Map> deliveredCancel =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + deliveredId + "/cancel",
            HttpMethod.POST,
            bearer(
                opsToken, Map.of("reason", "too late", "refund_amount", 10, "refund_to", "WALLET")),
            Map.class);
    assertThat(deliveredCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(deliveredCancel)).isEqualTo("ORDER_DELIVERED");

    // AC4 + AC5: finance refund on delivered + exceeds
    ResponseEntity<Map> elig =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + deliveredId + "/refund-eligibility",
            HttpMethod.GET,
            bearer(finToken, null),
            Map.class);
    assertThat(elig.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(elig).get("cancellation_eligible")).isEqualTo(false);

    long beforeBalance =
        jdbc.queryForObject(
            "SELECT COALESCE(wallet_balance_paise, 0) FROM customers WHERE id = ?",
            Long.class,
            customerId);
    ResponseEntity<Map> partial =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + deliveredId + "/refund",
            HttpMethod.POST,
            bearerIdem(
                finToken,
                Map.of(
                    "amount",
                    50.00,
                    "refund_to",
                    "WALLET",
                    "reason",
                    "missing item",
                    "notes",
                    "ok"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(partial).get("status")).isEqualTo("PROCESSED");
    long afterBalance =
        jdbc.queryForObject(
            "SELECT COALESCE(wallet_balance_paise, 0) FROM customers WHERE id = ?",
            Long.class,
            customerId);
    assertThat(afterBalance - beforeBalance).isEqualTo(5000L);

    ResponseEntity<Map> exceeds =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + deliveredId + "/refund",
            HttpMethod.POST,
            bearerIdem(
                finToken,
                Map.of("amount", 250.00, "refund_to", "WALLET", "reason", "too much"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(exceeds.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(exceeds)).isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    // finance cannot cancel
    ResponseEntity<Map> finCancel =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + packingOrder + "/cancel",
            HttpMethod.POST,
            bearer(
                finToken, Map.of("reason", "finance", "refund_amount", 10, "refund_to", "WALLET")),
            Map.class);
    assertThat(finCancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    // AC6: auto-cancel timeout → SYSTEM cancellation + refund (UPI paid)
    String timeoutId = placeAndConfirmUpi(customerToken, med);
    jdbc.update(
        "UPDATE orders SET confirmed_at = NOW() - INTERVAL '11 minutes' WHERE id = ?::uuid",
        timeoutId);
    assertThat(lifecycle.cancelTimedOutAcceptances()).isGreaterThanOrEqualTo(1);
    String byType =
        jdbc.queryForObject(
            "SELECT cancelled_by_type FROM order_cancellation WHERE order_id = ?::uuid",
            String.class,
            timeoutId);
    assertThat(byType).isEqualTo("SYSTEM");
    Integer autoRefunds =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM refund WHERE order_id = ?::uuid", Integer.class, timeoutId);
    assertThat(autoRefunds).isGreaterThanOrEqualTo(1);

    // webhook refund.processed
    String body =
        "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\""
            + rzRefundId
            + "\"}}}}";
    String sig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, body);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Razorpay-Signature", sig);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    ResponseEntity<Map> wh =
        rest.exchange(
            baseUrl() + "/api/v1/webhooks/razorpay/order-payment",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
    assertThat(wh.getStatusCode()).isEqualTo(HttpStatus.OK);
    String status =
        jdbc.queryForObject(
            "SELECT status FROM refund WHERE razorpay_refund_id = ?", String.class, rzRefundId);
    assertThat(status).isEqualTo("PROCESSED");
  }

  private String placeAndConfirmUpi(String customerToken, String med) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", med, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    String cartId = String.valueOf(data(add).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> upi =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", cartId, "payment_method", "UPI"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(upi.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String orderId = String.valueOf(data(upi).get("order_id"));
    @SuppressWarnings("unchecked")
    Map<String, Object> payment = (Map<String, Object>) data(upi).get("payment");
    String rzOrderId = String.valueOf(payment.get("razorpay_order_id"));
    String paymentId = "pay_cancel_" + UUID.randomUUID().toString().substring(0, 8);
    String sig = razorpay.signPayment(rzOrderId, paymentId);
    ResponseEntity<Map> confirm =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + orderId + "/payment/confirm",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("payment_id", paymentId, "payment_signature", sig),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
    return orderId;
  }

  private String placeCod(String customerToken, String med) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", med, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    String cartId = String.valueOf(data(add).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> placed =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", cartId, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return String.valueOf(data(placed).get("order_id"));
  }

  private void insertPharmacy(UUID id, String name, String code, double lat, double lng) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline, phone,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', '+91-8022334491', NOW(), NOW())",
        id,
        name,
        name,
        code,
        lat,
        lng,
        "{\"flat\":\"1\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}");
  }

  private void mapMedicine(String pharmacyToken, String medicineId, double price, int qty) {
    ResponseEntity<Map> mapped =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    price,
                    "stock_quantity",
                    qty)),
            Map.class);
    assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private String createMedicine(String adminToken, String name, String schedule, double mrp) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("salt_composition", "Salt " + UUID.randomUUID());
    body.put("manufacturer", "USV Ltd");
    body.put("category_id", CATEGORY.toString());
    body.put("form", "TABLET");
    body.put("pack_size", 10);
    body.put("pack_unit", "TABLET");
    body.put("schedule", schedule);
    body.put("hsn_code", "30041090");
    body.put("gst_pct", 12);
    body.put("mrp", mrp);
    body.put("is_rx_only", false);
    ResponseEntity<Map> created =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue",
            HttpMethod.POST,
            bearer(adminToken, body),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return String.valueOf(data(created).get("medicine_id"));
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", email, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(login).get("access_token"));
  }

  private String pharmacyLogin() {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/login",
            json(Map.of("identifier", OWNER_EMAIL, "password", PASSWORD)),
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
                    "it-cancel")),
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

  private static HttpEntity<?> bearerIdem(String token, Map<String, ?> body, String idem) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idem);
    return new HttpEntity<>(body, headers);
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
