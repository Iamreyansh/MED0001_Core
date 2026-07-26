package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.ListFilter;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.PageResult;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcCustomerProfileStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void findById_delegatesAndMapsRow() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    CustomerProfileRecord record = sampleRecord(id);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    assertThat(store.findById(id)).contains(record);
  }

  @Test
  void findById_empty_returnsEmpty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenReturn(List.of());

    assertThat(store.findById(Ids.newId())).isEmpty();
  }

  @Test
  void saveProfile_updatesRow() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    CustomerProfileRecord record = sampleRecord(Ids.newId());

    assertThat(store.saveProfile(record)).isEqualTo(record);
    verify(jdbc)
        .update(
            anyString(),
            eq(record.name()),
            eq(record.avatarUrl()),
            eq(record.dateOfBirth()),
            eq(record.gender()),
            eq(record.preferredLanguage()),
            eq(Timestamp.from(record.updatedAt())),
            eq(record.id()));
  }

  @Test
  void requestDeletion_updatesDeletionFields() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();

    store.requestDeletion(id, NOW, "privacy");

    verify(jdbc)
        .update(
            anyString(), eq(Timestamp.from(NOW)), eq("privacy"), eq(Timestamp.from(NOW)), eq(id));
  }

  @Test
  void cancelDeletion_clearsDeletionFields() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();

    store.cancelDeletion(id);

    verify(jdbc).update(anyString(), eq(id));
  }

  @Test
  void flag_andUnflag_delegateUpdates() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    UUID adminId = Ids.newId();

    store.flag(id, "OTHER", "note", adminId, NOW);
    verify(jdbc)
        .update(
            anyString(),
            eq("OTHER"),
            eq("note"),
            eq(adminId),
            eq(Timestamp.from(NOW)),
            eq(Timestamp.from(NOW)),
            eq(id));

    store.unflag(id);
    verify(jdbc).update(anyString(), eq(id));
  }

  @Test
  void list_appliesFiltersSortAndPagination() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    CustomerProfileRecord record = sampleRecord(Ids.newId());

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    PageResult page =
        store.list(new ListFilter(1, 10, "name", "desc", "ada", "VIP", false, "Bengaluru"));
    assertThat(page.total()).isOne();
    assertThat(page.items()).hasSize(1);
    org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForObject(anyString(), eq(Long.class), args.capture());
    assertThat(args.getValue()).contains("%ada%", "VIP", false, "Bengaluru");
  }

  @Test
  void list_escapesIlikeWildcardsInSearch() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    store.list(new ListFilter(1, 10, "created_at", "desc", "a%_b", null, null, null));

    org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForObject(anyString(), eq(Long.class), args.capture());
    assertThat(args.getValue()[0]).isEqualTo("%a\\%\\_b%");
  }

  @Test
  void lockCustomer_issuesForUpdate() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              return List.of(mapper.mapRow(rs, 0));
            });

    store.lockCustomer(id);

    verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), eq(id));
  }

  @Test
  void escapeIlike_escapesPercentUnderscoreAndBackslash() {
    assertThat(JdbcCustomerProfileStore.escapeIlike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
  }

  @Test
  void list_sortTotalOrdersAndAscOrder() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    CustomerProfileRecord record = sampleRecord(Ids.newId());

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    PageResult totalOrders =
        store.list(new ListFilter(1, 5, "total_orders", "asc", null, null, null, null));
    assertThat(totalOrders.total()).isZero();

    PageResult totalLtv =
        store.list(new ListFilter(1, 5, "total_ltv", "ASC", null, null, null, null));
    assertThat(totalLtv.items()).hasSize(1);
  }

  @Test
  void updateSegment_andInsertSegmentChange_delegate() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    UUID changeId = Ids.newId();

    store.updateSegment(id, "LOYAL");
    verify(jdbc).update(anyString(), eq("LOYAL"), eq(id));

    store.insertSegmentChange(changeId, id, "REGULAR", "LOYAL", 12, 100L, NOW);
    verify(jdbc)
        .update(
            anyString(),
            eq(changeId),
            eq(id),
            eq("REGULAR"),
            eq("LOYAL"),
            eq(12),
            eq(100L),
            eq(Timestamp.from(NOW)));
  }

  @Test
  void findAllActiveForSegmentRecompute_queriesActiveRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    CustomerProfileRecord record = sampleRecord(Ids.newId());

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    assertThat(store.findAllActiveForSegmentRecompute()).containsExactly(record);
  }

  @Test
  void findDueForAnonymisation_andAnonymise_delegate() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    CustomerProfileRecord record = sampleRecord(id);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(Timestamp.from(NOW))))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    assertThat(store.findDueForAnonymisation(NOW)).containsExactly(record);

    store.anonymise(id, "del_abc", NOW);
    verify(jdbc)
        .update(
            anyString(), eq("del_abc"), eq(Timestamp.from(NOW)), eq(Timestamp.from(NOW)), eq(id));
  }

  @Test
  void countNotificationsSince_nullCount_returnsZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id), eq(Timestamp.from(NOW))))
        .thenReturn(null);

    assertThat(store.countNotificationsSince(id, NOW)).isZero();
  }

  @Test
  void countNotificationsSince_returnsCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id), eq(Timestamp.from(NOW))))
        .thenReturn(2);

    assertThat(store.countNotificationsSince(id, NOW)).isEqualTo(2);
  }

  @Test
  void insertNotification_returnsId() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    UUID customerId = Ids.newId();
    UUID adminId = Ids.newId();

    assertThat(store.insertNotification(id, customerId, "SMS", null, "body", null, adminId, NOW))
        .isEqualTo(id);
    verify(jdbc)
        .update(
            anyString(),
            eq(id),
            eq(customerId),
            eq("SMS"),
            eq(null),
            eq("body"),
            eq(null),
            eq(adminId),
            eq(Timestamp.from(NOW)));
  }

  @Test
  void list_defaultCreatedAtSort() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    CustomerProfileRecord record = sampleRecord(Ids.newId());

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    PageResult page =
        store.list(new ListFilter(1, 5, "created_at", "desc", null, null, null, null));
    assertThat(page.items()).hasSize(1);
  }

  @Test
  void mapRow_withNonNullCancelRate_usesValue() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    CustomerProfileRecord record = sampleRecord(id);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    CustomerProfileRecord mapped = store.findById(id).orElseThrow();
    assertThat(mapped.cancelRate()).isEqualByComparingTo(new BigDecimal("0.20"));
  }

  @Test
  void mapRow_nullTimestampsAndCancelRate_useDefaults() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerProfileStore store = new JdbcCustomerProfileStore(jdbc);
    UUID id = Ids.newId();
    CustomerProfileRecord record = sampleRecord(id);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerProfileRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, false), 0));
            });

    CustomerProfileRecord mapped = store.findById(id).orElseThrow();
    assertThat(mapped.flaggedAt()).isNull();
    assertThat(mapped.lastOrderAt()).isNull();
    assertThat(mapped.deletionRequestedAt()).isNull();
    assertThat(mapped.createdAt()).isNull();
    assertThat(mapped.updatedAt()).isNull();
    assertThat(mapped.deletedAt()).isNull();
    assertThat(mapped.cancelRate()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private static CustomerProfileRecord sampleRecord(UUID id) {
    return new CustomerProfileRecord(
        id,
        "+919999999999",
        "Ada",
        "https://cdn.namma-medmate.in/avatars/a.png",
        LocalDate.of(1992, 3, 4),
        "FEMALE",
        "en",
        "REGULAR",
        "Bengaluru",
        false,
        null,
        null,
        null,
        NOW,
        10_000L,
        80,
        10,
        500_000L,
        new BigDecimal("0.20"),
        1,
        NOW,
        NOW,
        "reason",
        NOW.minusSeconds(3600),
        NOW,
        null);
  }

  private static ResultSet mockResultSet(CustomerProfileRecord record, boolean withTimestamps)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(record.id());
    when(rs.getString("phone")).thenReturn(record.phone());
    when(rs.getString("name")).thenReturn(record.name());
    when(rs.getString("avatar_url")).thenReturn(record.avatarUrl());
    when(rs.getObject("date_of_birth", LocalDate.class)).thenReturn(record.dateOfBirth());
    when(rs.getString("gender")).thenReturn(record.gender());
    when(rs.getString("preferred_language")).thenReturn(record.preferredLanguage());
    when(rs.getString("segment")).thenReturn(record.segment());
    when(rs.getString("city")).thenReturn(record.city());
    when(rs.getBoolean("is_flagged")).thenReturn(record.isFlagged());
    when(rs.getString("flag_reason")).thenReturn(record.flagReason());
    when(rs.getString("flag_note")).thenReturn(record.flagNote());
    when(rs.getObject("flagged_by")).thenReturn(record.flaggedBy());
    when(rs.getLong("wallet_balance_paise")).thenReturn(record.walletBalancePaise());
    when(rs.getInt("loyalty_points")).thenReturn(record.loyaltyPoints());
    when(rs.getInt("total_orders")).thenReturn(record.totalOrders());
    when(rs.getLong("total_ltv_paise")).thenReturn(record.totalLtvPaise());
    when(rs.getBigDecimal("cancel_rate")).thenReturn(withTimestamps ? record.cancelRate() : null);
    when(rs.getInt("dispute_count")).thenReturn(record.disputeCount());
    when(rs.getString("deletion_reason")).thenReturn(record.deletionReason());

    if (withTimestamps) {
      when(rs.getTimestamp("flagged_at")).thenReturn(Timestamp.from(record.flaggedAt()));
      when(rs.getTimestamp("last_order_at")).thenReturn(Timestamp.from(record.lastOrderAt()));
      when(rs.getTimestamp("deletion_requested_at"))
          .thenReturn(Timestamp.from(record.deletionRequestedAt()));
      when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(record.createdAt()));
      when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(record.updatedAt()));
      when(rs.getTimestamp("deleted_at")).thenReturn(null);
    } else {
      when(rs.getTimestamp("flagged_at")).thenReturn(null);
      when(rs.getTimestamp("last_order_at")).thenReturn(null);
      when(rs.getTimestamp("deletion_requested_at")).thenReturn(null);
      when(rs.getTimestamp("created_at")).thenReturn(null);
      when(rs.getTimestamp("updated_at")).thenReturn(null);
      when(rs.getTimestamp("deleted_at")).thenReturn(null);
    }
    return rs;
  }
}
