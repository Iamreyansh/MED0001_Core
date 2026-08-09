package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ListFilter;
import com.nammamedmate.inventory.domain.PoSentChannel;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
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
class JdbcPurchaseOrderStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPurchaseOrderStore store;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID poId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcPurchaseOrderStore(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void insertFindUpdateAndSoftCancel() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockPoRs(false), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockPoRs(true), 0)));

    PurchaseOrder po =
        new PurchaseOrder(
            poId,
            pharmacy,
            UUID.randomUUID(),
            "PO-2026-08-000001",
            PurchaseOrderStatus.DRAFT,
            UUID.randomUUID(),
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(store.insert(po)).isSameAs(po);
    assertThat(store.findById(pharmacy, poId)).isPresent();
    assertThat(
            store
                .update(poId, PurchaseOrderStatus.SENT, now, PoSentChannel.EMAIL, null, now)
                .sentChannel())
        .isEqualTo(PoSentChannel.WHATSAPP);
    store.softCancel(pharmacy, poId, now);
  }

  @Test
  @SuppressWarnings("unchecked")
  void insertWithSentDeletedAndNullUpdateFields() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockPoRs(true);
              when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    PurchaseOrder po =
        new PurchaseOrder(
            poId,
            pharmacy,
            UUID.randomUUID(),
            "PO-2026-08-000002",
            PurchaseOrderStatus.SENT,
            UUID.randomUUID(),
            now,
            PoSentChannel.WHATSAPP,
            UUID.randomUUID(),
            now,
            now,
            now);
    assertThat(store.insert(po)).isSameAs(po);
    assertThat(
            store
                .update(poId, PurchaseOrderStatus.RECEIVED, null, null, UUID.randomUUID(), now)
                .deletedAt())
        .isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void itemsAndTotals() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockItemRs(), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockItemRs(), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockItemRs(), 0)));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(3);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(500L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(2L);

    assertThat(
            store.insertItem(
                new PurchaseOrderItem(
                    UUID.randomUUID(), poId, pharmacy, UUID.randomUUID(), 2, 250L, now)))
        .isNotNull();
    assertThat(store.listItems(pharmacy, poId)).hasSize(1);
    assertThat(store.findItem(pharmacy, poId, UUID.randomUUID())).isPresent();
    assertThat(store.updateItemQuantity(UUID.randomUUID(), 9).quantity()).isEqualTo(5);
    assertThat(store.deleteItem(pharmacy, poId, UUID.randomUUID())).isTrue();
    assertThat(store.countItems(pharmacy, poId)).isEqualTo(3);
    assertThat(store.estimatedTotalPaise(pharmacy, poId)).isEqualTo(500L);
    assertThat(store.countOpen(pharmacy)).isEqualTo(2L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapItemNullPriceAndDeleteFalse() throws Exception {
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
    assertThat(store.deleteItem(pharmacy, poId, UUID.randomUUID())).isFalse();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockItemRs();
              when(rs.getObject("estimated_price_paise")).thenReturn(null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.listItems(pharmacy, poId).get(0).item().estimatedPricePaise()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void listAndSequences() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(4L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockListRs(), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("po_number")).thenReturn("PO-2026-08-000010");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });

    assertThat(store.list(new ListFilter(pharmacy, PurchaseOrderStatus.DRAFT, null, 1, 20)).total())
        .isEqualTo(4L);
    assertThat(store.list(new ListFilter(pharmacy, null, UUID.randomUUID(), 1, 20)).rows())
        .hasSize(1);
    assertThat(store.nextSequence(pharmacy, YearMonth.of(2026, 8))).isEqualTo(11);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockListRs();
              when(rs.getTimestamp("sent_at")).thenReturn(null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.list(new ListFilter(pharmacy, null, null, 1, 20)).total()).isEqualTo(0L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.nextSequence(pharmacy, YearMonth.of(2026, 8))).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("po_number")).thenReturn("PO-2026-08-XX");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.nextSequence(pharmacy, YearMonth.of(2026, 8))).isEqualTo(1);
  }

  @Test
  void nullCountsDefaultToZero() {
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(null);
    assertThat(store.countOpen(pharmacy)).isEqualTo(0L);
    assertThat(store.countItems(pharmacy, poId)).isEqualTo(0);
    assertThat(store.estimatedTotalPaise(pharmacy, poId)).isEqualTo(0L);
    assertThat(new PurchaseOrderStore.ListResult(null, 0).rows()).isEmpty();
  }

  private ResultSet mockPoRs(boolean sent) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(poId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("distributor_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("po_number")).thenReturn("PO-2026-08-000001");
    when(rs.getString("status")).thenReturn(sent ? "SENT" : "DRAFT");
    when(rs.getObject("created_by")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp("sent_at")).thenReturn(sent ? Timestamp.from(now) : null);
    when(rs.getString("sent_channel")).thenReturn(sent ? "WHATSAPP" : null);
    when(rs.getObject("grn_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  private ResultSet mockListRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(poId);
    when(rs.getString("po_number")).thenReturn("PO-2026-08-000001");
    when(rs.getString("firm_name")).thenReturn("Medico");
    when(rs.getInt("items_count")).thenReturn(1);
    when(rs.getLong("estimated_total_paise")).thenReturn(100L);
    when(rs.getString("status")).thenReturn("SENT");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockItemRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("po_id")).thenReturn(poId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
    when(rs.getInt("quantity")).thenReturn(5);
    when(rs.getObject("estimated_price_paise")).thenReturn(100L);
    when(rs.getLong("estimated_price_paise")).thenReturn(100L);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("product_name")).thenReturn("Para");
    when(rs.getLong("mrp_paise")).thenReturn(2000L);
    when(rs.getInt("gst_pct")).thenReturn(12);
    return rs;
  }
}
