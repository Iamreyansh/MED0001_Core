package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcScheduleShareTokenStore implements ScheduleShareTokenStore {

  private static final String SELECT =
      """
      SELECT id, token, customer_id, member_id, expires_at, created_at
      FROM schedule_share_token
      """;

  private final JdbcTemplate jdbc;
  private final RowMapper<ScheduleShareTokenRecord> rowMapper =
      (rs, i) ->
          new ScheduleShareTokenRecord(
              (UUID) rs.getObject("id"),
              rs.getString("token"),
              (UUID) rs.getObject("customer_id"),
              (UUID) rs.getObject("member_id"),
              rs.getTimestamp("expires_at").toInstant(),
              rs.getTimestamp("created_at").toInstant());

  public JdbcScheduleShareTokenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ScheduleShareTokenRecord insert(ScheduleShareTokenRecord token) {
    jdbc.update(
        """
        INSERT INTO schedule_share_token (id, token, customer_id, member_id, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        token.id(),
        token.token(),
        token.customerId(),
        token.memberId(),
        Timestamp.from(token.expiresAt()),
        Timestamp.from(token.createdAt()));
    return token;
  }

  @Override
  public Optional<ScheduleShareTokenRecord> findByToken(String token) {
    List<ScheduleShareTokenRecord> rows = jdbc.query(SELECT + " WHERE token = ?", rowMapper, token);
    return rows.stream().findFirst();
  }
}
