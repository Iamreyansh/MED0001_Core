package com.nammamedmate.pos.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
class JdbcPosStoresTest {

  @Mock JdbcTemplate jdbc;
  JdbcPosCartStore cartStore;
  JdbcInvoiceStore invoiceStore;
  Instant now = Instant.parse("2026-07-24T12:00:00Z");
  UUID pharmacy = UUID.randomUUID();
  UUID cartId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    cartStore = new JdbcPosCartStore(jdbc);
    invoiceStore = new JdbcInvoiceStore(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
  }

  @Test
  void parseDiscountTypeBranches() {
    assertThat(JdbcPosCartStore.parseDiscountType(null)).isNull();
    assertThat(JdbcPosCartStore.parseDiscountType("PERCENTAGE")).isEqualTo(DiscountType.PERCENTAGE);
  }

  @Test
  void cartStoreCrud() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              if (sql.contains("pos_cart_item")) {
                return List.of(mapper.mapRow(mockItemRs(), 0));
              }
              return List.of(mapper.mapRow(mockCartRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockItemRs(), 0));
            });

    PosCart cart =
        new PosCart(
            cartId,
            pharmacy,
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            DiscountType.FLAT_RS,
            BigDecimal.TEN,
            100,
            200,
            20,
            100,
            PosCartStatus.ACTIVE,
            now.plusSeconds(100),
            null,
            null,
            now,
            now);
    cartStore.insert(cart);
    assertThat(cartStore.findById(pharmacy, cartId)).isPresent();
    cartStore.update(cart);
    cartStore.touchExpiry(cartId, now, now);

    PosCartItem item =
        PosCartItem.compute(
            UUID.randomUUID(),
            cartId,
            UUID.randomUUID(),
            "P",
            UUID.randomUUID(),
            "BN",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            100L,
            12,
            false,
            1,
            "H",
            now);
    cartStore.insertItem(item);
    assertThat(cartStore.findItem(cartId, item.id())).isPresent();
    assertThat(cartStore.listItems(cartId)).hasSize(1);
    cartStore.updateItem(item);
    cartStore.deleteItem(cartId, item.id());
    cartStore.deleteAllItems(cartId);
    cartStore.updateTotals(
        cartId, 1, 1, 0, 1, "PERCENTAGE", BigDecimal.TEN, null, now, now.plusSeconds(10));
    cartStore.markCompleted(cartId, UUID.randomUUID(), now);
    cartStore.abandonExpired(now);
    cartStore.attachCustomer(cartId, UUID.randomUUID(), "A", "+91", now, now);
    cartStore.setPrescribingDoctor(cartId, "Dr", now, now);
  }

  @Test
  void invoiceStoreCrud() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn("PHARM");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(invoiceStore.getOrCreateSettings(pharmacy).invoicePrefix()).isEqualTo("PHARM");

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(invoiceStore.getOrCreateSettings(pharmacy).invoicePrefix()).isEqualTo("INV");

    cartStore.updateTotals(cartId, 1, 1, 0, 1, null, null, null, now, now.plusSeconds(1));

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    assertThat(invoiceStore.nextSequence(pharmacy, 2026, 7)).isEqualTo(1);

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(5);
    assertThat(invoiceStore.nextSequence(pharmacy, 2026, 8)).isEqualTo(5);

    Invoice inv =
        new Invoice(
            UUID.randomUUID(),
            pharmacy,
            "INV-2026-07-000001",
            cartId,
            InvoiceChannel.COUNTER,
            null,
            null,
            null,
            null,
            100,
            0,
            10,
            100,
            PaymentMethod.CASH,
            PaymentStatus.PAID,
            null,
            100,
            0,
            0,
            InvoiceStatus.ACTIVE,
            "https://cdn.example/x.pdf",
            now);
    invoiceStore.insert(inv);
    invoiceStore.insertItems(
        List.of(
            InvoiceItem.fromCartItem(
                UUID.randomUUID(),
                inv.id(),
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    UUID.randomUUID(),
                    "P",
                    UUID.randomUUID(),
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    100L,
                    12,
                    false,
                    1,
                    null,
                    now),
                now)));

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv2 -> {
              RowMapper<?> mapper = inv2.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(inv.id());
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("invoice_number")).thenReturn(inv.invoiceNumber());
              when(rs.getObject("cart_id")).thenReturn(cartId);
              when(rs.getString("channel")).thenReturn("COUNTER");
              when(rs.getObject("customer_id")).thenReturn(null);
              when(rs.getString("customer_name")).thenReturn(null);
              when(rs.getString("customer_phone")).thenReturn(null);
              when(rs.getString("prescribing_doctor")).thenReturn(null);
              when(rs.getLong("subtotal_paise")).thenReturn(100L);
              when(rs.getLong("discount_amount_paise")).thenReturn(0L);
              when(rs.getLong("gst_total_paise")).thenReturn(10L);
              when(rs.getLong("grand_total_paise")).thenReturn(100L);
              when(rs.getString("payment_method")).thenReturn("CASH");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("payment_reference")).thenReturn(null);
              when(rs.getLong("amount_paid_paise")).thenReturn(100L);
              when(rs.getLong("change_due_paise")).thenReturn(0L);
              when(rs.getLong("mrp_savings_paise")).thenReturn(0L);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("invoice_pdf_url")).thenReturn("u");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(invoiceStore.findById(pharmacy, inv.id())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv2 -> {
              String sql = inv2.getArgument(0);
              if (sql.contains("FROM invoice WHERE id = ?")) {
                RowMapper<?> mapper = inv2.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id")).thenReturn(inv.id());
                when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
                when(rs.getString("invoice_number")).thenReturn(inv.invoiceNumber());
                when(rs.getObject("cart_id")).thenReturn(cartId);
                when(rs.getString("channel")).thenReturn("COUNTER");
                when(rs.getObject("customer_id")).thenReturn(null);
                when(rs.getString("customer_name")).thenReturn(null);
                when(rs.getString("customer_phone")).thenReturn(null);
                when(rs.getString("prescribing_doctor")).thenReturn(null);
                when(rs.getLong("subtotal_paise")).thenReturn(100L);
                when(rs.getLong("discount_amount_paise")).thenReturn(0L);
                when(rs.getLong("gst_total_paise")).thenReturn(10L);
                when(rs.getLong("grand_total_paise")).thenReturn(100L);
                when(rs.getString("payment_method")).thenReturn("CASH");
                when(rs.getString("payment_status")).thenReturn("PAID");
                when(rs.getString("payment_reference")).thenReturn(null);
                when(rs.getLong("amount_paid_paise")).thenReturn(100L);
                when(rs.getLong("change_due_paise")).thenReturn(0L);
                when(rs.getLong("mrp_savings_paise")).thenReturn(0L);
                when(rs.getString("status")).thenReturn("ACTIVE");
                when(rs.getString("invoice_pdf_url")).thenReturn("u");
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
                return List.of(mapper.mapRow(rs, 0));
              }
              RowMapper<?> mapper = inv2.getArgument(1);
              return List.of(mapper.mapRow(mockItemRs(), 0));
            });
    assertThat(invoiceStore.findByIdAny(inv.id())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              ResultSet rs = mockCartRs();
              when(rs.getString("discount_type")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    PosCart nullDiscount =
        new PosCart(
            cartId,
            pharmacy,
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            PosCartStatus.ACTIVE,
            now.plusSeconds(100),
            null,
            null,
            now,
            now);
    cartStore.insert(nullDiscount);
    cartStore.update(nullDiscount);

    InvoiceItem noExpiry =
        new InvoiceItem(
            UUID.randomUUID(),
            inv.id(),
            UUID.randomUUID(),
            "P",
            null,
            null,
            null,
            null,
            1,
            1,
            false,
            100L,
            12,
            100L,
            11L,
            100L,
            false,
            now);
    invoiceStore.insertItems(List.of(noExpiry));
  }

  @Test
  void invoiceListCountAndItems() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("invoice_number")).thenReturn("INV-2026-07-000001");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getString("channel")).thenReturn("COUNTER");
              when(rs.getObject("customer_id")).thenReturn(null);
              when(rs.getString("customer_name")).thenReturn("Priya");
              when(rs.getString("customer_phone")).thenReturn("+91");
              when(rs.getString("prescribing_doctor")).thenReturn(null);
              when(rs.getLong("subtotal_paise")).thenReturn(100L);
              when(rs.getLong("discount_amount_paise")).thenReturn(0L);
              when(rs.getLong("gst_total_paise")).thenReturn(10L);
              when(rs.getLong("grand_total_paise")).thenReturn(100L);
              when(rs.getString("payment_method")).thenReturn("CASH");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("payment_reference")).thenReturn(null);
              when(rs.getLong("amount_paid_paise")).thenReturn(100L);
              when(rs.getLong("change_due_paise")).thenReturn(0L);
              when(rs.getLong("mrp_savings_paise")).thenReturn(0L);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("invoice_pdf_url")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getInt("items_count")).thenReturn(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

    assertThat(
            invoiceStore.list(
                pharmacy,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "CASH",
                "COUNTER",
                "Priya",
                20,
                0))
        .hasSize(1);
    assertThat(
            invoiceStore.count(
                pharmacy,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "CASH",
                "COUNTER",
                "Priya"))
        .isEqualTo(1L);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("product_name")).thenReturn("P");
              when(rs.getString("hsn_code")).thenReturn("3004");
              when(rs.getObject("batch_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("batch_number")).thenReturn("BN");
              when(rs.getDate("expiry_date")).thenReturn(Date.valueOf(LocalDate.of(2027, 1, 1)));
              when(rs.getObject("pack_size")).thenReturn(15);
              when(rs.getInt("quantity")).thenReturn(1);
              when(rs.getBoolean("is_loose")).thenReturn(false);
              when(rs.getLong("unit_price_paise")).thenReturn(100L);
              when(rs.getInt("gst_pct")).thenReturn(12);
              when(rs.getLong("line_subtotal_paise")).thenReturn(100L);
              when(rs.getLong("gst_amount_paise")).thenReturn(11L);
              when(rs.getLong("line_total_paise")).thenReturn(100L);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(invoiceStore.listItems(UUID.randomUUID())).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(invoiceStore.count(pharmacy, null, null, null, null, null)).isZero();
    assertThat(invoiceStore.count(pharmacy, null, null, " ", " ", " ")).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("invoice_number")).thenReturn("INV-2026-07-000001");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getString("channel")).thenReturn("COUNTER");
              when(rs.getObject("customer_id")).thenReturn(null);
              when(rs.getString("customer_name")).thenReturn("Priya");
              when(rs.getString("customer_phone")).thenReturn("+91");
              when(rs.getString("prescribing_doctor")).thenReturn(null);
              when(rs.getLong("subtotal_paise")).thenReturn(100L);
              when(rs.getLong("discount_amount_paise")).thenReturn(0L);
              when(rs.getLong("gst_total_paise")).thenReturn(10L);
              when(rs.getLong("grand_total_paise")).thenReturn(100L);
              when(rs.getString("payment_method")).thenReturn("CASH");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("payment_reference")).thenReturn(null);
              when(rs.getLong("amount_paid_paise")).thenReturn(100L);
              when(rs.getLong("change_due_paise")).thenReturn(0L);
              when(rs.getLong("mrp_savings_paise")).thenReturn(0L);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("invoice_pdf_url")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getInt("items_count")).thenReturn(2);
              when(rs.getLong("bill_count")).thenReturn(1L);
              when(rs.getLong("units_sold")).thenReturn(2L);
              when(rs.getLong("gross_revenue_paise")).thenReturn(100L);
              when(rs.getLong("gst_collected_paise")).thenReturn(10L);
              when(rs.getLong("credit_outstanding_paise")).thenReturn(0L);
              when(rs.getString("payment_method")).thenReturn("CASH");
              when(rs.getLong("cnt")).thenReturn(1L);
              when(rs.getLong("amount_paise")).thenReturn(100L);
              when(rs.getLong("revenue_paise")).thenReturn(100L);
              when(rs.getString("product_name")).thenReturn("Para");
              when(rs.getLong("units")).thenReturn(2L);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    assertThat(
            invoiceStore.listSales(
                pharmacy,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "CASH",
                "PAID",
                "COUNTER",
                "Priya",
                "amount",
                "asc",
                20,
                0))
        .hasSize(1);
    assertThat(
            invoiceStore.countSales(
                pharmacy,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "CASH",
                "PAID",
                "COUNTER",
                "Priya"))
        .isEqualTo(2L);
    assertThat(
            invoiceStore
                .periodSummary(
                    pharmacy,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    null,
                    null,
                    null,
                    null)
                .billCount())
        .isEqualTo(1L);
    assertThat(
            invoiceStore.paymentModeMix(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .hasSize(1);
    assertThat(
            invoiceStore.channelRevenue(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .hasSize(1);
    assertThat(
            invoiceStore.topProducts(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 5))
        .hasSize(1);
    invoiceStore.markPaid(pharmacy, UUID.randomUUID(), PaymentStatus.PAID, "RCPT-1", 100L, now);
    assertThat(
            invoiceStore.listSales(
                pharmacy, null, null, null, null, null, null, "invoice_number", "desc", 10, 0))
        .hasSize(1);
    assertThat(
            invoiceStore.listSales(
                pharmacy, null, null, null, null, null, null, "date", null, 10, 0))
        .hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(invoiceStore.countSales(pharmacy, null, null, null, null, null, null)).isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(invoiceStore.periodSummary(pharmacy, null, null, null, null, null, null).billCount())
        .isZero();

    // salesOrderBy + appendFilters blank payment_status branch
    assertThat(invoiceStore.listSales(pharmacy, null, null, " ", " ", " ", " ", null, "desc", 5, 0))
        .isEmpty();
    assertThat(
            invoiceStore.listSales(pharmacy, null, null, null, null, null, null, " ", "ASC", 5, 0))
        .isEmpty();
    assertThat(
            invoiceStore.listSales(
                pharmacy, null, null, null, null, null, null, "weird", "nope", 5, 0))
        .isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("product_name")).thenReturn("P");
              when(rs.getString("hsn_code")).thenReturn(null);
              when(rs.getObject("batch_id")).thenReturn(null);
              when(rs.getString("batch_number")).thenReturn(null);
              when(rs.getDate("expiry_date")).thenReturn(null);
              when(rs.getObject("pack_size")).thenReturn(null);
              when(rs.getInt("quantity")).thenReturn(1);
              when(rs.getBoolean("is_loose")).thenReturn(false);
              when(rs.getLong("unit_price_paise")).thenReturn(100L);
              when(rs.getInt("gst_pct")).thenReturn(12);
              when(rs.getLong("line_subtotal_paise")).thenReturn(100L);
              when(rs.getLong("gst_amount_paise")).thenReturn(11L);
              when(rs.getLong("line_total_paise")).thenReturn(100L);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(invoiceStore.listItems(UUID.randomUUID()).getFirst().expiryDate()).isNull();
  }

  private ResultSet mockCartRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(cartId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("staff_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("customer_id")).thenReturn(null);
    when(rs.getString("customer_name")).thenReturn(null);
    when(rs.getString("customer_phone")).thenReturn(null);
    when(rs.getString("prescribing_doctor")).thenReturn(null);
    when(rs.getString("discount_type")).thenReturn("FLAT_RS");
    when(rs.getBigDecimal("discount_value")).thenReturn(BigDecimal.TEN);
    when(rs.getLong("discount_amount_paise")).thenReturn(0L);
    when(rs.getLong("subtotal_paise")).thenReturn(0L);
    when(rs.getLong("gst_total_paise")).thenReturn(0L);
    when(rs.getLong("grand_total_paise")).thenReturn(0L);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(10)));
    when(rs.getObject("invoice_id")).thenReturn(null);
    when(rs.getObject("applied_offer_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockItemRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("cart_id")).thenReturn(cartId);
    when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("product_name")).thenReturn("P");
    when(rs.getObject("batch_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("batch_number")).thenReturn("BN");
    when(rs.getDate("expiry_date")).thenReturn(Date.valueOf(LocalDate.of(2027, 1, 1)));
    when(rs.getInt("quantity")).thenReturn(1);
    when(rs.getBoolean("is_loose")).thenReturn(false);
    when(rs.getLong("unit_price_paise")).thenReturn(100L);
    when(rs.getInt("gst_pct")).thenReturn(12);
    when(rs.getLong("line_subtotal_paise")).thenReturn(100L);
    when(rs.getLong("gst_amount_paise")).thenReturn(11L);
    when(rs.getLong("line_total_paise")).thenReturn(100L);
    when(rs.getBoolean("is_rx_only")).thenReturn(false);
    when(rs.getInt("pack_size")).thenReturn(1);
    when(rs.getString("hsn_code")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
