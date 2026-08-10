package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionInUsePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PrescriptionOrderBridgeConfigTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T07:30:00Z"), ZoneOffset.UTC);
  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
  private final PrescriptionOrderBridgeConfig config = new PrescriptionOrderBridgeConfig();

  @Test
  @SuppressWarnings("unchecked")
  void prescriptionPort_findVerifiedAndBroadcast() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PrescriptionPort port = config.jdbcPrescriptionPort(jdbc, om, clock);
    UUID rx = UUID.randomUUID();
    UUID cust = UUID.randomUUID();

    assertThat(port.findVerified(null, cust)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(cust)))
        .thenReturn(List.of("VERIFIED"));
    assertThat(port.findVerified(rx, cust))
        .contains(new PrescriptionPort.PrescriptionRef(rx, "VERIFIED"));

    String medsJson =
        "[{\"name\":\"Metformin 500mg\",\"quantity\":\"60 tablets\",\"dosage\":\"1-0-1\"}]";
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              // Simulate ResultSet via direct detail construction path — use real mapper carefully.
              return List.of(
                  new PrescriptionPort.PrescriptionDetail(
                      rx,
                      "VERIFIED",
                      false,
                      List.of(new PrescriptionPort.MedicineLine("Metformin 500mg", 60))));
            });
    // Re-stub with ResultSet-less approach: call parse via findForBroadcast using custom answer
    // that invokes mapper with a mock ResultSet.
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(rs.getString("status")).thenReturn("VERIFIED");
    when(rs.getTimestamp("expires_at"))
        .thenReturn(java.sql.Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")));
    when(rs.getString("medicines_extracted")).thenReturn(medsJson);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    Optional<PrescriptionPort.PrescriptionDetail> detail = port.findForBroadcast(rx, cust);
    assertThat(detail).isPresent();
    assertThat(detail.get().medicines()).hasSize(1);
    assertThat(detail.get().medicines().get(0).quantity()).isEqualTo(60);
    assertThat(detail.get().expired()).isFalse();

    when(rs.getString("medicines_extracted")).thenReturn("not-json");
    assertThat(port.findForBroadcast(rx, cust).get().medicines()).isEmpty();

    when(rs.getString("medicines_extracted")).thenReturn("[{\"name\":\"X\",\"quantity\":3}]");
    assertThat(port.findForBroadcast(rx, cust).get().medicines().get(0).quantity()).isEqualTo(3);

    when(rs.getString("status")).thenReturn("EXPIRED");
    when(rs.getTimestamp("expires_at"))
        .thenReturn(java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
    when(rs.getString("medicines_extracted")).thenReturn("[]");
    assertThat(port.findForBroadcast(rx, cust).get().expired()).isTrue();

    assertThat(port.findForBroadcast(null, cust)).isEmpty();
  }

  @Test
  void orderLink_attachAndMismatch() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderLinkPort link = config.jdbcOrderLinkPort(jdbc, clock);
    UUID cust = UUID.randomUUID();
    UUID cart = UUID.randomUUID();
    UUID rx = UUID.randomUUID();

    when(jdbc.queryForList(anyString(), eq(cart), eq(cust))).thenReturn(List.of());
    assertThatThrownBy(() -> link.attachToCart(cust, cart, rx))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_NOT_FOUND");

    UUID other = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), eq(cart), eq(cust)))
        .thenReturn(List.of(Map.of("id", cart, "prescription_id", other, "status", "ACTIVE")));
    assertThatThrownBy(() -> link.attachToCart(cust, cart, rx))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_PRESCRIPTION_MISMATCH");

    when(jdbc.queryForList(anyString(), eq(cart), eq(cust)))
        .thenReturn(List.of(Map.of("id", cart, "prescription_id", rx, "status", "ACTIVE")));
    link.attachToCart(cust, cart, rx); // same rx — no-op success

    when(jdbc.queryForList(anyString(), eq(cart), eq(cust)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    cart,
                    "prescription_id",
                    "",
                    "status",
                    "ACTIVE"))); // empty string won't happen; use null map
    // null prescription_id
    java.util.HashMap<String, Object> row = new java.util.HashMap<>();
    row.put("id", cart);
    row.put("prescription_id", null);
    row.put("status", "ACTIVE");
    when(jdbc.queryForList(anyString(), eq(cart), eq(cust))).thenReturn(List.of(row));
    when(jdbc.update(anyString(), any(), any(), eq(cart), eq(cust))).thenReturn(1);
    link.attachToCart(cust, cart, rx);

    when(jdbc.update(anyString(), any(), any(), eq(cart), eq(cust))).thenReturn(0);
    assertThatThrownBy(() -> link.attachToCart(cust, cart, rx))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_NOT_FOUND");
  }

  @Test
  void inUsePort() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PrescriptionInUsePort port = config.jdbcPrescriptionInUsePort(jdbc);
    UUID rx = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(rx))).thenReturn(true);
    assertThat(port.isInUse(rx)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(rx)))
        .thenReturn(false)
        .thenReturn(true);
    assertThat(port.isInUse(rx)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(rx))).thenReturn(false);
    assertThat(port.isInUse(rx)).isFalse();
  }
}
