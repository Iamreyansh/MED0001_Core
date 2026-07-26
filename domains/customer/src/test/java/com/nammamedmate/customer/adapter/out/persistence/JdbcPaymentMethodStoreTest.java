package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.PaymentMethodStore.PaymentMethodRecord;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPaymentMethodStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void listByCustomer_mapsRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    PaymentMethodRecord record = sampleUpi(Ids.newId(), Ids.newId(), true);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<PaymentMethodRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, false), 0));
            });

    assertThat(store.listByCustomer(record.customerId())).containsExactly(record);
  }

  @Test
  void countByCustomerAndType_null_returnsZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);

    assertThat(store.countByCustomerAndType(Ids.newId(), "UPI")).isZero();
  }

  @Test
  void countByCustomerAndType_returnsCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    UUID customerId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(customerId), eq("CARD")))
        .thenReturn(2);

    assertThat(store.countByCustomerAndType(customerId, "CARD")).isEqualTo(2);
  }

  @Test
  void findByIdForCustomer_empty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    assertThat(store.findByIdForCustomer(Ids.newId(), Ids.newId())).isEmpty();
  }

  @Test
  void findByIdForCustomer_mapsDeletedAt() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    PaymentMethodRecord record =
        new PaymentMethodRecord(
            Ids.newId(),
            Ids.newId(),
            "CARD",
            false,
            "Nick",
            null,
            null,
            "enc-token",
            "4242",
            "VISA",
            "CREDIT",
            null,
            NOW,
            NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id()), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<PaymentMethodRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    assertThat(store.findByIdForCustomer(record.id(), record.customerId())).contains(record);
  }

  @Test
  void listByCustomerAndType_queries() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    PaymentMethodRecord record = sampleUpi(Ids.newId(), Ids.newId(), false);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId()), eq("UPI")))
        .thenAnswer(
            inv -> {
              RowMapper<PaymentMethodRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, false), 0));
            });

    assertThat(store.listByCustomerAndType(record.customerId(), "UPI")).containsExactly(record);
  }

  @Test
  void findByIdempotencyKey_maps() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    PaymentMethodRecord record =
        new PaymentMethodRecord(
            Ids.newId(),
            Ids.newId(),
            "UPI",
            false,
            "GPay",
            "enc-upi",
            "***@okaxis",
            null,
            null,
            null,
            null,
            "idem-1",
            NOW,
            null);

    when(jdbc.query(anyString(), any(RowMapper.class), eq("idem-1")))
        .thenAnswer(
            inv -> {
              RowMapper<PaymentMethodRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, false), 0));
            });

    assertThat(store.findByIdempotencyKey("idem-1")).contains(record);
  }

  @Test
  void insert_bindsColumns() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    PaymentMethodRecord record = sampleUpi(Ids.newId(), Ids.newId(), false);

    store.insert(record);

    verify(jdbc)
        .update(
            anyString(),
            eq(record.id()),
            eq(record.customerId()),
            eq("UPI"),
            eq(false),
            eq(record.nickname()),
            eq(record.upiIdEncrypted()),
            eq(record.upiHandle()),
            eq(null),
            eq(null),
            eq(null),
            eq(null),
            eq(null),
            eq(Timestamp.from(NOW)));
  }

  @Test
  void softDelete_clearDefault_setDefault() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    UUID id = Ids.newId();
    UUID customerId = Ids.newId();

    store.softDelete(id, customerId, NOW);
    store.clearDefaultFlags(customerId);
    store.setDefault(id, customerId);

    verify(jdbc).update(anyString(), eq(Timestamp.from(NOW)), eq(id), eq(customerId));
    verify(jdbc).update(anyString(), eq(customerId));
    verify(jdbc).update(anyString(), eq(id), eq(customerId));
  }

  @Test
  void findDefaultMethodId_maps() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    UUID customerId = Ids.newId();
    UUID methodId = Ids.newId();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(customerId)))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(methodId);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findDefaultMethodId(customerId)).contains(methodId);
  }

  @Test
  void findDefaultMethodId_empty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPaymentMethodStore store = new JdbcPaymentMethodStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenReturn(List.of());

    assertThat(store.findDefaultMethodId(Ids.newId())).isEmpty();
  }

  private static PaymentMethodRecord sampleUpi(UUID id, UUID customerId, boolean isDefault) {
    return new PaymentMethodRecord(
        id,
        customerId,
        "UPI",
        isDefault,
        "GPay",
        "enc-upi",
        "***@okaxis",
        null,
        null,
        null,
        null,
        null,
        NOW,
        null);
  }

  private static ResultSet mockResultSet(PaymentMethodRecord record, boolean withDeleted)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(record.id());
    when(rs.getObject("customer_id")).thenReturn(record.customerId());
    when(rs.getString("type")).thenReturn(record.type());
    when(rs.getBoolean("is_default")).thenReturn(record.isDefault());
    when(rs.getString("nickname")).thenReturn(record.nickname());
    when(rs.getString("upi_id")).thenReturn(record.upiIdEncrypted());
    when(rs.getString("upi_handle")).thenReturn(record.upiHandle());
    when(rs.getString("razorpay_token_id")).thenReturn(record.razorpayTokenEncrypted());
    when(rs.getString("card_last4")).thenReturn(record.cardLast4());
    when(rs.getString("card_network")).thenReturn(record.cardNetwork());
    when(rs.getString("card_type")).thenReturn(record.cardType());
    when(rs.getString("idempotency_key")).thenReturn(record.idempotencyKey());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(record.createdAt()));
    when(rs.getTimestamp("deleted_at"))
        .thenReturn(
            withDeleted && record.deletedAt() != null ? Timestamp.from(record.deletedAt()) : null);
    return rs;
  }
}
