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
          first_order_id, reward_amount_paise, referee_rewarded_at, referrer_rewarded_at,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.id(),
        event.refereeCustomerId(),
        event.referrerCustomerId(),
        event.referralCode(),
        event.status().name(),
        event.firstOrderId(),
        event.rewardAmountPaise(),
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
    return new ReferralEventRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("referee_customer_id"),
        (UUID) rs.getObject("referrer_customer_id"),
        rs.getString("referral_code"),
        ReferralEventStatus.valueOf(rs.getString("status")),
        (UUID) rs.getObject("first_order_id"),
        rs.getLong("reward_amount_paise"),
        refereeAt == null ? null : refereeAt.toInstant(),
        referrerAt == null ? null : referrerAt.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
