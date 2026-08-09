package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** EPIC-012 STORY-008: ledger browse/export + append-only DB enforcement. */
class FinancialLedgerIT extends AbstractApiIT {

  private static final UUID FINANCE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0081");
  private static final UUID SUPPORT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0082");
  private static final String ADMIN_PASSWORD = "LedgerAdmin1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void seed() {
    flushRedis("admin:ip:*");
    flushRedis("admin:user:*");
    seedAdmin(FINANCE_ID, "finance-ledger@test.in", "admin_finance");
    seedAdmin(SUPPORT_ID, "support-ledger@test.in", "admin_support");
    // Test cleanup only — production path keeps append-only triggers enabled.
    jdbc.execute("ALTER TABLE financial_ledger DISABLE TRIGGER USER");
    jdbc.update("DELETE FROM financial_ledger");
    jdbc.execute("ALTER TABLE financial_ledger ENABLE TRIGGER USER");
  }

  @Test
  void ac002_updateAndDeleteRejected() {
    UUID id = UUID.randomUUID();
    UUID ref = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, created_at)
        VALUES (?, 'ORDER_GMV', ?, 'PAYMENT', 100, 0, 'it', NOW())
        """,
        id,
        ref);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE financial_ledger SET credit_paise = 200 WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("append-only");

    assertThatThrownBy(() -> jdbc.update("DELETE FROM financial_ledger WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void ac003_ac005_ac007_browseExportAndForbidden() {
    UUID payoutId = UUID.randomUUID();
    UUID gmvId = UUID.randomUUID();
    UUID settlementRef = UUID.randomUUID();
    UUID paymentRef = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, created_at)
        VALUES (?, 'ORDER_GMV', ?, 'PAYMENT', 49500, 0, 'gmv', NOW() - INTERVAL '1 hour')
        """,
        gmvId,
        paymentRef);
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, created_at)
        VALUES (?, 'PAYOUT_PHARMACY', ?, 'SETTLEMENT', 0, 10000, 'payout', NOW())
        """,
        payoutId,
        settlementRef);

    String financeToken = adminLogin("finance-ledger@test.in");
    ResponseEntity<Map> browse =
        rest.exchange(
            baseUrl()
                + "/api/v1/admin/finance/ledger?type=PAYOUT_PHARMACY&page=1&limit=50&order=desc",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(browse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = data(browse);
    assertThat(data).containsKeys("kpi_chips", "entries");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().get("type")).isEqualTo("PAYOUT_PHARMACY");
    assertThat(entries.getFirst().get("running_balance")).isNotNull();

    ResponseEntity<Map> tooLarge =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/ledger/export?from=2026-01-01&to=2026-07-31",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(tooLarge)).isEqualTo("DATE_RANGE_TOO_LARGE");

    ResponseEntity<Map> export =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/ledger/export?from=2026-07-01&to=2026-07-31",
            HttpMethod.GET,
            bearer(financeToken),
            Map.class);
    assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(export)).containsKeys("download_url", "record_count", "expires_at");

    String supportToken = adminLogin("support-ledger@test.in");
    ResponseEntity<Map> forbidden =
        rest.exchange(
            baseUrl() + "/api/v1/admin/finance/ledger",
            HttpMethod.GET,
            bearer(supportToken),
            Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void codDepositUniqueConstraint() {
    UUID depositId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, created_at)
        VALUES (?, 'COD_DEPOSIT', ?, 'COD_DEPOSIT', 50000, 0, 'deposit', NOW())
        """,
        UUID.randomUUID(),
        depositId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO financial_ledger (
                      id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
                      description, created_at)
                    VALUES (?, 'COD_DEPOSIT', ?, 'COD_DEPOSIT', 50000, 0, 'dup', NOW())
                    """,
                    UUID.randomUUID(),
                    depositId))
        .isInstanceOf(DataIntegrityViolationException.class);
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
        "Ledger Admin",
        email,
        hash,
        role);
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
