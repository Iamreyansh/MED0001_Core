package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcCartAndStubsCoverageTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcCartStore carts;
  private final UUID cartId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private final UUID cust = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID itemId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private final UUID product = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @BeforeEach
  void setUp() {
    carts = new JdbcCartStore(jdbc, new ObjectMapper());
  }

  @Test
  void cartCrudAndJsonRoundTrip() throws Exception {
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    Cart cart =
        new Cart(
            cartId,
            cust,
            null,
            List.of(
                new CartItem(
                    itemId, product, 2, 8500, true, "Metformin", "USV", "10 tablets", null)),
            "NAMMA25",
            6375,
            null,
            null,
            CartStatus.ACTIVE,
            now,
            now);

    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    assertThat(carts.insert(cart)).isSameAs(cart);

    when(jdbc.update(
            anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    assertThat(carts.update(cart)).isSameAs(cart);
    when(jdbc.update(
            anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0);
    assertThatThrownBy(() -> carts.update(cart)).isInstanceOf(IllegalStateException.class);

    when(jdbc.update(anyString(), any(Timestamp.class))).thenReturn(3);
    assertThat(carts.abandonStale(now)).isEqualTo(3);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              ResultSet rs = mockCartRs();
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(carts.findActiveByCustomer(cust)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockCartRs(), 0));
            });
    assertThat(carts.findById(cartId)).isPresent();
    assertThat(carts.findById(cartId).orElseThrow().items().getFirst().quantity()).isEqualTo(2);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId))).thenReturn(List.of());
    assertThat(carts.findById(cartId)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(cust))).thenReturn(List.of());
    assertThat(carts.findActiveByCustomer(cust)).isEmpty();

    // empty / blank / bad json + boolean string rx flag
    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(cartId);
              when(rs.getObject("customer_id")).thenReturn(cust);
              when(rs.getObject("pharmacy_id")).thenReturn(null);
              when(rs.getString("items")).thenReturn("");
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getTimestamp("created_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-08-08T00:00:00Z")));
              when(rs.getTimestamp("updated_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-08-08T00:00:00Z")));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(carts.findById(cartId).orElseThrow().items()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              ResultSet rs = mockCartRs();
              when(rs.getString("items"))
                  .thenReturn(
                      "[{\"item_id\":\""
                          + itemId
                          + "\",\"product_id\":\""
                          + product
                          + "\",\"quantity\":1,\"unit_price_paise\":100,"
                          + "\"is_rx_required\":\"true\",\"name\":null,\"brand\":null,"
                          + "\"pack_size\":null,\"image_url\":\"null\"}]");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(carts.findById(cartId).orElseThrow().items().getFirst().rxRequired()).isTrue();

    assertThatThrownBy(
            () -> {
              when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
                  .thenAnswer(
                      inv -> {
                        RowMapper<Cart> mapper = inv.getArgument(1);
                        ResultSet rs = mockCartRs();
                        when(rs.getString("items")).thenReturn("not-json");
                        return List.of(mapper.mapRow(rs, 0));
                      });
              carts.findById(cartId);
            })
        .isInstanceOf(IllegalStateException.class);

    ObjectMapper boom = mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    JdbcCartStore broken = new JdbcCartStore(jdbc, boom);
    assertThatThrownBy(
            () ->
                broken.insert(
                    new Cart(
                        cartId,
                        cust,
                        null,
                        List.of(
                            new CartItem(
                                itemId, product, 1, 100, false, "n", "b", "p", "http://x")),
                        null,
                        0,
                        null,
                        null,
                        CartStatus.ACTIVE,
                        Instant.parse("2026-08-08T00:00:00Z"),
                        Instant.parse("2026-08-08T00:00:00Z"))))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              ResultSet rs = mockCartRs();
              when(rs.getString("items")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(carts.findById(cartId).orElseThrow().items()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cartId)))
        .thenAnswer(
            inv -> {
              RowMapper<Cart> mapper = inv.getArgument(1);
              ResultSet rs = mockCartRs();
              when(rs.getString("items"))
                  .thenReturn(
                      "[{\"item_id\":\""
                          + itemId
                          + "\",\"product_id\":\""
                          + product
                          + "\",\"quantity\":1,\"unit_price_paise\":100,"
                          + "\"is_rx_required\":false,\"name\":\"n\",\"brand\":\"b\","
                          + "\"pack_size\":\"p\",\"image_url\":\"http://cdn/x.png\"}]");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(carts.findById(cartId).orElseThrow().items().getFirst().imageUrl())
        .isEqualTo("http://cdn/x.png");
  }

  @Test
  void addressWalletPrescriptionZoneAdapters() throws Exception {
    JdbcCustomerAddressAdapter addr = new JdbcCustomerAddressAdapter(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(addr.findForCustomer(UUID.randomUUID(), cust)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(cust))).thenReturn(List.of());
    assertThat(addr.findDefault(cust)).isEmpty();

    UUID aid = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(aid), eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(aid);
              when(rs.getObject("customer_id")).thenReturn(cust);
              when(rs.getString("label")).thenReturn("Home");
              when(rs.getString("flat_building")).thenReturn("42");
              when(rs.getString("area_locality")).thenReturn("Koramangala");
              when(rs.getString("city")).thenReturn("Bengaluru");
              when(rs.getString("pincode")).thenReturn("560034");
              when(rs.getDouble("latitude")).thenReturn(12.9);
              when(rs.getDouble("longitude")).thenReturn(77.6);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(addr.findForCustomer(aid, cust)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(aid);
              when(rs.getObject("customer_id")).thenReturn(cust);
              when(rs.getString("label")).thenReturn("Home");
              when(rs.getString("flat_building")).thenReturn("42");
              when(rs.getString("area_locality")).thenReturn("Koramangala");
              when(rs.getString("city")).thenReturn("Bengaluru");
              when(rs.getString("pincode")).thenReturn("560034");
              when(rs.getDouble("latitude")).thenReturn(12.9);
              when(rs.getDouble("longitude")).thenReturn(77.6);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(addr.findDefault(cust)).isPresent();

    JdbcWalletBalanceAdapter wallet = new JdbcWalletBalanceAdapter(jdbc);
    when(jdbc.query(
            eq("SELECT wallet_balance_paise FROM customers WHERE id = ?"),
            any(RowMapper.class),
            eq(cust)))
        .thenAnswer(
            inv -> {
              RowMapper<Long> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getLong(1)).thenReturn(12500L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(wallet.balancePaise(cust)).isEqualTo(12500L);
    when(jdbc.query(
            eq("SELECT wallet_balance_paise FROM customers WHERE id = ?"),
            any(RowMapper.class),
            eq(cust)))
        .thenReturn(List.of());
    assertThat(wallet.balancePaise(cust)).isEqualTo(0L);

    StubPrescriptionAdapter rx = new StubPrescriptionAdapter();
    assertThat(rx.findVerified(null, cust)).isEmpty();
    assertThat(rx.findVerified(UUID.randomUUID(), cust)).isPresent();
    assertThat(rx.findVerified(StubPrescriptionAdapter.NOT_FOUND_ID, cust)).isEmpty();
    assertThat(rx.findVerified(StubPrescriptionAdapter.EXPIRED_ID, cust)).isEmpty();
    assertThat(rx.findForBroadcast(null, cust)).isEmpty();
    assertThat(rx.findForBroadcast(StubPrescriptionAdapter.NOT_FOUND_ID, cust)).isEmpty();
    assertThat(rx.findForBroadcast(StubPrescriptionAdapter.EXPIRED_ID, cust))
        .hasValueSatisfying(d -> assertThat(d.expired()).isTrue());
    assertThat(rx.findForBroadcast(UUID.randomUUID(), cust))
        .hasValueSatisfying(
            d -> {
              assertThat(d.expired()).isFalse();
              assertThat(d.medicines()).isNotEmpty();
            });
    assertThat(
            new PrescriptionPort.PrescriptionDetail(UUID.randomUUID(), "VERIFIED", false, null)
                .medicines())
        .isEmpty();

    StubZoneMembershipAdapter zone = new StubZoneMembershipAdapter();
    assertThat(zone.isInPharmacyZone(UUID.randomUUID(), 1, 2)).isTrue();
    assertThat(zone.isInPharmacyZone(null, 1, 2)).isFalse();
  }

  private ResultSet mockCartRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(cartId);
    when(rs.getObject("customer_id")).thenReturn(cust);
    when(rs.getObject("pharmacy_id")).thenReturn(null);
    when(rs.getString("items"))
        .thenReturn(
            "[{\"item_id\":\""
                + itemId
                + "\",\"product_id\":\""
                + product
                + "\",\"quantity\":2,\"unit_price_paise\":8500,\"line_total_paise\":17000,"
                + "\"is_rx_required\":true,\"name\":\"Metformin\",\"brand\":\"USV\","
                + "\"pack_size\":\"10 tablets\",\"image_url\":null}]");
    when(rs.getString("coupon_code")).thenReturn("NAMMA25");
    when(rs.getLong("coupon_discount_paise")).thenReturn(6375L);
    when(rs.getObject("prescription_id")).thenReturn(null);
    when(rs.getObject("delivery_address_id")).thenReturn(null);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getTimestamp("created_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-08T00:00:00Z")));
    when(rs.getTimestamp("updated_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-08T00:00:00Z")));
    return rs;
  }
}
