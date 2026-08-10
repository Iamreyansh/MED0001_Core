package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.application.port.out.ReferralStore.AdminOverviewChips;
import com.nammamedmate.customer.application.port.out.ReferralStore.AdminReferralRow;
import com.nammamedmate.customer.application.port.out.ReferralStore.ProgramSettingsRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralEventRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.TopReferrerRow;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
            event.refereeRewardAmountPaise(),
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
            any(),
            any()))
        .thenReturn(1);
    assertThat(store.insertEvent(event)).isEqualTo(event);
    assertThat(store.insertEvent(rewarded)).isEqualTo(rewarded);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(event.refereeCustomerId())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralEventRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockEventRs(event, false), 0));
            });
    assertThat(store.findEventByReferee(event.refereeCustomerId())).contains(event);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(rewarded.id())))
        .thenAnswer(
            inv -> {
              RowMapper<ReferralEventRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockEventRs(rewarded, true), 0));
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

  @Test
  void settingsShareAndAdminQueries() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcReferralStore store = new JdbcReferralStore(jdbc);
    ProgramSettingsRecord settings =
        new ProgramSettingsRecord(
            ReferralStore.PROGRAM_SETTINGS_ID, 10_000L, 10_000L, true, 365, "c", null, NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(ReferralStore.PROGRAM_SETTINGS_ID)))
        .thenAnswer(
            inv -> {
              RowMapper<ProgramSettingsRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(settings.id());
              when(rs.getLong("reward_for_referrer_paise")).thenReturn(10_000L);
              when(rs.getLong("reward_for_referee_paise")).thenReturn(10_000L);
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getInt("reward_expiry_days")).thenReturn(365);
              when(rs.getString("conditions")).thenReturn("c");
              when(rs.getObject("updated_by")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.getProgramSettings()).isEqualTo(settings);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(ReferralStore.PROGRAM_SETTINGS_ID)))
        .thenReturn(List.of());
    assertThatThrownBy(store::getProgramSettings).isInstanceOf(IllegalStateException.class);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    assertThat(store.updateProgramSettings(settings)).isEqualTo(settings);

    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    store.insertShareEvent(Ids.newId(), Ids.newId(), "WHATSAPP", NOW);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(3L);
    assertThat(store.countShareEvents(Ids.newId())).isEqualTo(3L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(null);
    assertThat(store.countShareEvents(Ids.newId())).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getLong("total_referrals")).thenReturn(5L);
              when(rs.getLong("converted")).thenReturn(2L);
              when(rs.getLong("pending_rewards")).thenReturn(10_000L);
              when(rs.getLong("total_paid")).thenReturn(40_000L);
              return List.of(mapper.mapRow(rs, 0));
            });
    AdminOverviewChips chips = store.chips();
    assertThat(chips.totalReferrals()).isEqualTo(5L);
    assertThat(chips.convertedReferrals()).isEqualTo(2L);

    UUID customerId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
        .thenAnswer(
            inv -> {
              RowMapper<TopReferrerRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("customer_id")).thenReturn(customerId);
              when(rs.getString("name")).thenReturn("Priya");
              when(rs.getInt("total_referrals")).thenReturn(10);
              when(rs.getInt("converted_referrals")).thenReturn(8);
              when(rs.getLong("total_earned_paise")).thenReturn(80_000L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.topReferrers(10)).hasSize(1);

    UUID eventId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              RowMapper<AdminReferralRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(eventId);
              when(rs.getString("referrer_name")).thenReturn("A");
              when(rs.getString("referee_name")).thenReturn("B");
              when(rs.getString("phone")).thenReturn("+9198");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getTimestamp("referrer_rewarded_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listAdminReferrals(null, 20, 0)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq("REWARDED"), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              RowMapper<AdminReferralRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(eventId);
              when(rs.getString("referrer_name")).thenReturn("A");
              when(rs.getString("referee_name")).thenReturn("B");
              when(rs.getString("phone")).thenReturn("+9198");
              when(rs.getString("status")).thenReturn("REWARDED");
              when(rs.getTimestamp("referrer_rewarded_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listAdminReferrals(ReferralEventStatus.REWARDED, 20, 0)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(9L);
    assertThat(store.countAdminReferrals(null)).isEqualTo(9L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.countAdminReferrals(null)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("PENDING"))).thenReturn(4L);
    assertThat(store.countAdminReferrals(ReferralEventStatus.PENDING)).isEqualTo(4L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("CANCELLED"))).thenReturn(null);
    assertThat(store.countAdminReferrals(ReferralEventStatus.CANCELLED)).isZero();
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

  private static ResultSet mockEventRs(ReferralEventRecord e, boolean nullRefereeReward)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(e.id());
    when(rs.getObject("referee_customer_id")).thenReturn(e.refereeCustomerId());
    when(rs.getObject("referrer_customer_id")).thenReturn(e.referrerCustomerId());
    when(rs.getString("referral_code")).thenReturn(e.referralCode());
    when(rs.getString("status")).thenReturn(e.status().name());
    when(rs.getObject("first_order_id")).thenReturn(e.firstOrderId());
    when(rs.getLong("reward_amount_paise")).thenReturn(e.rewardAmountPaise());
    when(rs.getLong("referee_reward_amount_paise")).thenReturn(e.refereeRewardAmountPaise());
    when(rs.wasNull()).thenReturn(nullRefereeReward);
    when(rs.getTimestamp("referee_rewarded_at"))
        .thenReturn(e.refereeRewardedAt() == null ? null : Timestamp.from(e.refereeRewardedAt()));
    when(rs.getTimestamp("referrer_rewarded_at"))
        .thenReturn(e.referrerRewardedAt() == null ? null : Timestamp.from(e.referrerRewardedAt()));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(e.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(e.updatedAt()));
    return rs;
  }
}
