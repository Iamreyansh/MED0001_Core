package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcReferralStore implements ReferralStore {

  private static final RowMapper<ReferralRecord> REFERRAL_ROW = JdbcReferralStore::mapReferral;
  private static final RowMapper<ReferralEventRecord> EVENT_ROW = JdbcReferralStore::mapEvent;
  private static final RowMapper<ProgramSettingsRecord> SETTINGS_ROW =
      JdbcReferralStore::mapSettings;

  private final JdbcTemplate jdbc;

  public JdbcReferralStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ReferralRecord> findByCustomerId(UUID customerId) {
    List<ReferralRecord> rows =
        jdbc.query(
            "SELECT * FROM customer_referrals WHERE customer_id = ?", REFERRAL_ROW, customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ReferralRecord> findByCode(String referralCode) {
    List<ReferralRecord> rows =
        jdbc.query(
            "SELECT * FROM customer_referrals WHERE referral_code = ?", REFERRAL_ROW, referralCode);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ReferralRecord> lockByCustomerId(UUID customerId) {
    List<ReferralRecord> rows =
        jdbc.query(
            "SELECT * FROM customer_referrals WHERE customer_id = ? FOR UPDATE",
            REFERRAL_ROW,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public ReferralRecord insert(ReferralRecord record) {
    jdbc.update(
        """
        INSERT INTO customer_referrals (
          id, customer_id, referral_code, total_referrals, converted_referrals,
          total_earned_paise, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.customerId(),
        record.referralCode(),
        record.totalReferrals(),
        record.convertedReferrals(),
        record.totalEarnedPaise(),
        Timestamp.from(record.createdAt()));
    return record;
  }

  @Override
  public ReferralRecord update(ReferralRecord record) {
    jdbc.update(
        """
        UPDATE customer_referrals SET
          total_referrals = ?,
          converted_referrals = ?,
          total_earned_paise = ?
        WHERE id = ?
        """,
        record.totalReferrals(),
        record.convertedReferrals(),
        record.totalEarnedPaise(),
        record.id());
    return record;
  }

  @Override
  public boolean codeExists(String referralCode) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_referrals WHERE referral_code = ?",
            Long.class,
            referralCode);
    return count != null && count > 0;
  }

  @Override
  public ReferralEventRecord insertEvent(ReferralEventRecord event) {
    jdbc.update(
        """
        INSERT INTO referral_events (
          id, referee_customer_id, referrer_customer_id, referral_code, status,
          first_order_id, reward_amount_paise, referee_reward_amount_paise,
          referee_rewarded_at, referrer_rewarded_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.id(),
        event.refereeCustomerId(),
        event.referrerCustomerId(),
        event.referralCode(),
        event.status().name(),
        event.firstOrderId(),
        event.rewardAmountPaise(),
        event.refereeRewardAmountPaise(),
        toTs(event.refereeRewardedAt()),
        toTs(event.referrerRewardedAt()),
        Timestamp.from(event.createdAt()),
        Timestamp.from(event.updatedAt()));
    return event;
  }

  @Override
  public Optional<ReferralEventRecord> findEventByReferee(UUID refereeCustomerId) {
    List<ReferralEventRecord> rows =
        jdbc.query(
            "SELECT * FROM referral_events WHERE referee_customer_id = ?",
            EVENT_ROW,
            refereeCustomerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ReferralEventRecord> lockEventById(UUID eventId) {
    List<ReferralEventRecord> rows =
        jdbc.query("SELECT * FROM referral_events WHERE id = ? FOR UPDATE", EVENT_ROW, eventId);
    return rows.stream().findFirst();
  }

  @Override
  public ReferralEventRecord updateEvent(ReferralEventRecord event) {
    jdbc.update(
        """
        UPDATE referral_events SET
          status = ?,
          first_order_id = ?,
          referee_rewarded_at = ?,
          referrer_rewarded_at = ?,
          updated_at = ?
        WHERE id = ?
        """,
        event.status().name(),
        event.firstOrderId(),
        toTs(event.refereeRewardedAt()),
        toTs(event.referrerRewardedAt()),
        Timestamp.from(event.updatedAt()),
        event.id());
    return event;
  }

  @Override
  public long countEventsByReferrerAndStatus(UUID referrerCustomerId, ReferralEventStatus status) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM referral_events
            WHERE referrer_customer_id = ? AND status = ?
            """,
            Long.class,
            referrerCustomerId,
            status.name());
    return count == null ? 0L : count;
  }

  @Override
  public ProgramSettingsRecord getProgramSettings() {
    List<ProgramSettingsRecord> rows =
        jdbc.query(
            "SELECT * FROM referral_program_settings WHERE id = ?",
            SETTINGS_ROW,
            PROGRAM_SETTINGS_ID);
    return rows.stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("referral_program_settings missing"));
  }

  @Override
  public ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord settings) {
    jdbc.update(
        """
        UPDATE referral_program_settings SET
          reward_for_referrer_paise = ?,
          reward_for_referee_paise = ?,
          is_active = ?,
          reward_expiry_days = ?,
          conditions = ?,
          updated_by = ?,
          updated_at = ?
        WHERE id = ?
        """,
        settings.rewardForReferrerPaise(),
        settings.rewardForRefereePaise(),
        settings.active(),
        settings.rewardExpiryDays(),
        settings.conditions(),
        settings.updatedBy(),
        Timestamp.from(settings.updatedAt()),
        settings.id());
    return settings;
  }

  @Override
  public void insertShareEvent(UUID id, UUID customerId, String channel, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO referral_share_events (id, customer_id, channel, created_at)
        VALUES (?, ?, ?, ?)
        """,
        id,
        customerId,
        channel,
        Timestamp.from(createdAt));
  }

  @Override
  public long countShareEvents(UUID customerId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM referral_share_events WHERE customer_id = ?",
            Long.class,
            customerId);
    return count == null ? 0L : count;
  }

  @Override
  public AdminOverviewChips chips() {
    return jdbc.query(
            """
            SELECT
              (SELECT COUNT(*) FROM referral_events) AS total_referrals,
              (SELECT COUNT(*) FROM referral_events WHERE status = 'REWARDED') AS converted,
              (SELECT COALESCE(SUM(reward_amount_paise), 0) FROM referral_events
                 WHERE status = 'PENDING') AS pending_rewards,
              (SELECT COALESCE(SUM(reward_amount_paise + referee_reward_amount_paise), 0)
                 FROM referral_events WHERE status = 'REWARDED') AS total_paid
            """,
            (rs, i) ->
                new AdminOverviewChips(
                    rs.getLong("total_referrals"),
                    rs.getLong("converted"),
                    rs.getLong("pending_rewards"),
                    rs.getLong("total_paid")))
        .getFirst();
  }

  @Override
  public List<TopReferrerRow> topReferrers(int limit) {
    return jdbc.query(
        """
        SELECT cr.customer_id, c.name, cr.total_referrals, cr.converted_referrals,
               cr.total_earned_paise
        FROM customer_referrals cr
        JOIN customers c ON c.id = cr.customer_id AND c.deleted_at IS NULL
        WHERE cr.converted_referrals > 0
        ORDER BY cr.converted_referrals DESC, cr.total_earned_paise DESC
        LIMIT ?
        """,
        (rs, i) ->
            new TopReferrerRow(
                (UUID) rs.getObject("customer_id"),
                rs.getString("name"),
                rs.getInt("total_referrals"),
                rs.getInt("converted_referrals"),
                rs.getLong("total_earned_paise")),
        limit);
  }

  @Override
  public List<AdminReferralRow> listAdminReferrals(
      ReferralEventStatus statusFilter, int limit, int offset) {
    if (statusFilter == null) {
      return jdbc.query(
          """
          SELECT re.id, ref.name AS referrer_name, ree.name AS referee_name, ree.phone,
                 re.status, re.referrer_rewarded_at, re.created_at
          FROM referral_events re
          JOIN customers ref ON ref.id = re.referrer_customer_id
          JOIN customers ree ON ree.id = re.referee_customer_id
          ORDER BY re.created_at DESC
          LIMIT ? OFFSET ?
          """,
          ADMIN_ROW,
          limit,
          offset);
    }
    return jdbc.query(
        """
        SELECT re.id, ref.name AS referrer_name, ree.name AS referee_name, ree.phone,
               re.status, re.referrer_rewarded_at, re.created_at
        FROM referral_events re
        JOIN customers ref ON ref.id = re.referrer_customer_id
        JOIN customers ree ON ree.id = re.referee_customer_id
        WHERE re.status = ?
        ORDER BY re.created_at DESC
        LIMIT ? OFFSET ?
        """,
        ADMIN_ROW,
        statusFilter.name(),
        limit,
        offset);
  }

  @Override
  public long countAdminReferrals(ReferralEventStatus statusFilter) {
    if (statusFilter == null) {
      Long count = jdbc.queryForObject("SELECT COUNT(*) FROM referral_events", Long.class);
      return count == null ? 0L : count;
    }
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM referral_events WHERE status = ?",
            Long.class,
            statusFilter.name());
    return count == null ? 0L : count;
  }

  private static final RowMapper<AdminReferralRow> ADMIN_ROW =
      (rs, i) -> {
        Timestamp credited = rs.getTimestamp("referrer_rewarded_at");
        return new AdminReferralRow(
            (UUID) rs.getObject("id"),
            rs.getString("referrer_name"),
            rs.getString("referee_name"),
            rs.getString("phone"),
            ReferralEventStatus.valueOf(rs.getString("status")),
            credited == null ? null : credited.toInstant(),
            rs.getTimestamp("created_at").toInstant());
      };

  private static Timestamp toTs(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static ReferralRecord mapReferral(ResultSet rs, int rowNum) throws SQLException {
    return new ReferralRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("referral_code"),
        rs.getInt("total_referrals"),
        rs.getInt("converted_referrals"),
        rs.getLong("total_earned_paise"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static ReferralEventRecord mapEvent(ResultSet rs, int rowNum) throws SQLException {
    Timestamp refereeAt = rs.getTimestamp("referee_rewarded_at");
    Timestamp referrerAt = rs.getTimestamp("referrer_rewarded_at");
    long refereeReward = rs.getLong("referee_reward_amount_paise");
    if (rs.wasNull()) {
      refereeReward = rs.getLong("reward_amount_paise");
    }
    return new ReferralEventRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("referee_customer_id"),
        (UUID) rs.getObject("referrer_customer_id"),
        rs.getString("referral_code"),
        ReferralEventStatus.valueOf(rs.getString("status")),
        (UUID) rs.getObject("first_order_id"),
        rs.getLong("reward_amount_paise"),
        refereeReward,
        refereeAt == null ? null : refereeAt.toInstant(),
        referrerAt == null ? null : referrerAt.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static ProgramSettingsRecord mapSettings(ResultSet rs, int rowNum) throws SQLException {
    return new ProgramSettingsRecord(
        (UUID) rs.getObject("id"),
        rs.getLong("reward_for_referrer_paise"),
        rs.getLong("reward_for_referee_paise"),
        rs.getBoolean("is_active"),
        rs.getInt("reward_expiry_days"),
        rs.getString("conditions"),
        (UUID) rs.getObject("updated_by"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
