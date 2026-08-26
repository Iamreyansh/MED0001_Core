package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

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

/** EPIC-012 STORY-009: finance overview KPI / P&L / cash / ratios. */
class FinanceOverviewIT extends AbstractApiIT {

  private static final UUID FINANCE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0091");
  private static final UUID SUPPORT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0092");
  private static final UUID FINANCE_CUST = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0f01");
  private static final UUID FINANCE_PH = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0f02");
  private static final UUID FINANCE_ADDR = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0f03");
  private static final UUID FINANCE_CART = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0f04");
  private static final String ADMIN_PASSWORD = "OverviewAdmin1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void seed() {
    flushRedis("admin:ip:*");
    flushRedis("admin:user:*");
    flushRedis("finance:overview:*");
    seedAdmin(FINANCE_ID, "finance-overview@test.in", "admin_finance");
    seedAdmin(SUPPORT_ID, "support-overview@test.in", "admin_support");
    jdbc.execute("ALTER TABLE financial_ledger DISABLE TRIGGER USER");
    jdbc.update("DELETE FROM financial_ledger");
    jdbc.execute("ALTER TABLE financial_ledger ENABLE TRIGGER USER");
    jdbc.update("DELETE FROM payment WHERE customer_id = ?", FINANCE_CUST);
    jdbc.update("DELETE FROM orders WHERE customer_id = ?", FINANCE_CUST);
    jdbc.update("DELETE FROM carts WHERE customer_id = ?", FINANCE_CUST);
    jdbc.update("DELETE FROM customer_addresses WHERE customer_id = ?", FINANCE_CUST);
    jdbc.update("DELETE FROM customers WHERE id = ?", FINANCE_CUST);
    jdbc.update("DELETE FROM pharmacies WHERE code = 'PHM-F1' OR id = ?", FINANCE_PH);
  }

  @Test
  void ac001_ac006_ac007_ac008_kpiPnlCacheAndAuth() {
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    seedFinanceOrder(orderId);
    jdbc.update(
        """
        INSERT INTO payment (
          id, order_id, customer_id, amount_paise, wallet_portion_paise, gateway_portion_paise,
          method, status, gateway_fee_paise, created_at, updated_at)
        VALUES (?, ?, ?, 18500000, 0, 18500000, 'UPI', 'CAPTURED', 273600, NOW(), NOW())
        """,
        paymentId,
        orderId,
        FINANCE_CUST);
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, created_at)
        VALUES (?, 'COMMISSION', ?, 'PAYMENT', 1480000, 0, 'c', NOW()),
               (?, 'ORDER_GMV', ?, 'PAYMENT', 18500000, 0, 'g', NOW()),
               (?, 'GATEWAY_FEE', ?, 'PAYMENT', 0, 273600, 'f', NOW())
        """,
        UUID.randomUUID(),
        paymentId,
        UUID.randomUUID(),
        paymentId,
        UUID.randomUUID(),
        paymentId);

    String financeToken = adminLogin("finance-overview@test.in");
    ResponseEntity<Map> kpi1 =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/kpi",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(kpi1.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> kpiData = data(kpi1);
    assertThat(kpiData.get("gmv_today")).isEqualTo(185000.0);
    String asOf = String.valueOf(kpiData.get("as_of"));

    ResponseEntity<Map> kpi2 =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/kpi",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(data(kpi2).get("as_of")).isEqualTo(asOf);

    ResponseEntity<Map> customMissing =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/pnl?period=CUSTOM",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(customMissing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(customMissing)).isEqualTo("CUSTOM_DATES_REQUIRED");

    ResponseEntity<Map> pnl =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/pnl?period=7D",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(pnl.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(pnl)).containsKeys("net_revenue", "gmv_chart", "gmv_breakdown_pie");

    ResponseEntity<Map> cash =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/cash-position?period=30D",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(cash.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(cash)).containsKey("platform_net");

    ResponseEntity<Map> ratios =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/ratios?period=30D",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(ratios.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(ratios)).containsKey("take_rate_pct");

    String supportToken = adminLogin("support-overview@test.in");
    ResponseEntity<Map> forbidden =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/kpi",
            HttpMethod.GET,
            bearer(supportToken),
            Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private void seedAdmin(UUID id, String email, String role) {
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", id);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", id);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", id, email);
    String hash = new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD);
    jdbc.update(
        """
        INSERT INTO admin_staff (
          id, name, email, password_hash, role, status, mfa_enabled, failed_login_attempts,
          created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'ACTIVE', false, 0, NOW(), NOW())
        """,
        id,
        "Overview Admin",
        email,
        hash,
        role);
  }

  private void seedFinanceOrder(UUID orderId) {
    jdbc.update(
        "INSERT INTO customers (id, phone, name, created_at, updated_at) VALUES (?, '+919999900091', 'Fin', NOW(), NOW())",
        FINANCE_CUST);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline, phone,"
            + " created_at, updated_at) VALUES (?, 'Fin Ph', 'Fin Ph', 'Bengaluru', 'GROWTH', 'PHM-F1',"
            + " 'ACTIVE', true, false, 12.93, 77.61,"
            + " '{\"flat\":\"1\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}'::jsonb,"
            + " 'tag', '+91-8022330091', NOW(), NOW())",
        FINANCE_PH);
    jdbc.update(
        """
        INSERT INTO customer_addresses (
          id, customer_id, label, flat_building, area_locality, city, state, pincode,
          latitude, longitude, is_default, created_at, updated_at)
        VALUES (?, ?, 'Home', '1', 'Koramangala', 'Bengaluru', 'KA', '560034',
          12.93, 77.61, true, NOW(), NOW())
        """,
        FINANCE_ADDR,
        FINANCE_CUST);
    jdbc.update(
        "INSERT INTO carts (id, customer_id, pharmacy_id, items, status, created_at, updated_at)"
            + " VALUES (?, ?, ?, '[]'::jsonb, 'CHECKED_OUT', NOW(), NOW())",
        FINANCE_CART,
        FINANCE_CUST,
        FINANCE_PH);
    jdbc.update(
        """
        INSERT INTO orders (
          id, order_number, customer_id, pharmacy_id, cart_id, items,
          item_total_paise, coupon_discount_paise, delivery_fee_paise, handling_fee_paise,
          wallet_applied_paise, total_payable_paise, payment_method, payment_status,
          delivery_address_id, status, created_at, updated_at)
        VALUES (?, 'ORD-FIN-0001', ?, ?, ?, '[]'::jsonb,
          18500000, 0, 0, 0, 0, 18500000, 'UPI', 'PAID',
          ?, 'DELIVERED', NOW(), NOW())
        """,
        orderId,
        FINANCE_CUST,
        FINANCE_PH,
        FINANCE_CART,
        FINANCE_ADDR);
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
