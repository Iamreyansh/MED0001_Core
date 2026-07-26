package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralEventRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralRecord;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcReferralStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void referralCrudAndCodeExists() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcReferralStore store = new JdbcReferralStore(jdbc);
    ReferralRecord record = sampleReferral();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockReferralRs(record), 0));
            });
    assertThat(store.findByCustomerId(record.customerId())).contains(record);
    assertThat(store.lockByCustomerId(record.customerId())).contains(record);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.referralCode())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockReferralRs(record), 0));
            });
    assertThat(store.findByCode(record.referralCode())).contains(record);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.insert(record)).isEqualTo(record);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.update(record)).isEqualTo(record);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("MEDRAM7"))).thenReturn(1L);
    assertThat(store.codeExists("MEDRAM7")).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("MEDNONE"))).thenReturn(0L);
    assertThat(store.codeExists("MEDNONE")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("MEDNULL"))).thenReturn(null);
    assertThat(store.codeExists("MEDNULL")).isFalse();
  }

  @Test
  void eventCrudAndCount() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcReferralStore store = new JdbcReferralStore(jdbc);
    ReferralEventRecord event = sampleEvent();
    ReferralEventRecord rewarded =
        new ReferralEventRecord(
            event.id(),
            event.refereeCustomerId(),
            event.referrerCustomerId(),
            event.referralCode(),
            ReferralEventStatus.REWARDED,
            Ids.newId(),
            event.rewardAmountPaise(),
            NOW,
            NOW,
            event.createdAt(),
            NOW);

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
    assertThat(store.insertEvent(event)).isEqualTo(event);
    assertThat(store.insertEvent(rewarded)).isEqualTo(rewarded);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(event.refereeCustomerId())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralEventRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockEventRs(event), 0));
            });
    assertThat(store.findEventByReferee(event.refereeCustomerId())).contains(event);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(rewarded.id())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralEventRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockEventRs(rewarded), 0));
            });
    assertThat(store.lockEventById(rewarded.id())).contains(rewarded);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.updateEvent(rewarded)).isEqualTo(rewarded);

    when(jdbc.queryForObject(
            anyString(), eq(Long.class), eq(event.referrerCustomerId()), eq("PENDING")))
        .thenReturn(2L);
    assertThat(
            store.countEventsByReferrerAndStatus(
                event.referrerCustomerId(), ReferralEventStatus.PENDING))
        .isEqualTo(2L);
    when(jdbc.queryForObject(
            anyString(), eq(Long.class), eq(event.referrerCustomerId()), eq("CANCELLED")))
        .thenReturn(null);
    assertThat(
            store.countEventsByReferrerAndStatus(
                event.referrerCustomerId(), ReferralEventStatus.CANCELLED))
        .isZero();
  }

  private static ReferralRecord sampleReferral() {
    return new ReferralRecord(Ids.newId(), Ids.newId(), "MEDRAM7", 1, 0, 0L, NOW);
  }

  private static ReferralEventRecord sampleEvent() {
    return new ReferralEventRecord(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        "MEDRAM7",
        ReferralEventStatus.PENDING,
        null,
        10_000L,
        null,
        null,
        NOW,
        NOW);
  }

  private static ResultSet mockReferralRs(ReferralRecord r) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getObject("customer_id")).thenReturn(r.customerId());
    when(rs.getString("referral_code")).thenReturn(r.referralCode());
    when(rs.getInt("total_referrals")).thenReturn(r.totalReferrals());
    when(rs.getInt("converted_referrals")).thenReturn(r.convertedReferrals());
    when(rs.getLong("total_earned_paise")).thenReturn(r.totalEarnedPaise());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(r.createdAt()));
    return rs;
  }

  private static ResultSet mockEventRs(ReferralEventRecord e) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(e.id());
    when(rs.getObject("referee_customer_id")).thenReturn(e.refereeCustomerId());
    when(rs.getObject("referrer_customer_id")).thenReturn(e.referrerCustomerId());
    when(rs.getString("referral_code")).thenReturn(e.referralCode());
    when(rs.getString("status")).thenReturn(e.status().name());
    when(rs.getObject("first_order_id")).thenReturn(e.firstOrderId());
    when(rs.getLong("reward_amount_paise")).thenReturn(e.rewardAmountPaise());
    when(rs.getTimestamp("referee_rewarded_at"))
        .thenReturn(e.refereeRewardedAt() == null ? null : Timestamp.from(e.refereeRewardedAt()));
    when(rs.getTimestamp("referrer_rewarded_at"))
        .thenReturn(e.referrerRewardedAt() == null ? null : Timestamp.from(e.referrerRewardedAt()));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(e.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(e.updatedAt()));
    return rs;
  }
}
