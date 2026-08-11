package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
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
public class JdbcCareCircleMemberStore implements CareCircleMemberStore {

  private static final String SELECT =
      """
      SELECT id, customer_id, name, age, relationship, avatar_emoji, avatar_color,
             is_self, created_at, updated_at, deleted_at
      FROM care_circle_member
      """;

  private static final RowMapper<MemberRecord> ROW = JdbcCareCircleMemberStore::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcCareCircleMemberStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<MemberRecord> listByCustomer(UUID customerId) {
    return jdbc.query(
        SELECT
            + """
            WHERE customer_id = ? AND deleted_at IS NULL
            ORDER BY is_self DESC, created_at ASC
            """,
        ROW,
        customerId);
  }

  @Override
  public int countByCustomer(UUID customerId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM care_circle_member
            WHERE customer_id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            customerId);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<MemberRecord> findById(UUID memberId) {
    List<MemberRecord> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", ROW, memberId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<MemberRecord> findSelf(UUID customerId) {
    List<MemberRecord> rows =
        jdbc.query(
            SELECT
                + """
                WHERE customer_id = ? AND is_self = TRUE AND deleted_at IS NULL
                """,
            ROW,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public MemberRecord insert(MemberRecord member) {
    jdbc.update(
        """
        INSERT INTO care_circle_member (
          id, customer_id, name, age, relationship, avatar_emoji, avatar_color,
          is_self, created_at, updated_at, deleted_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        member.id(),
        member.customerId(),
        member.name(),
        member.age(),
        member.relationship(),
        member.avatarEmoji(),
        member.avatarColor(),
        member.self(),
        Timestamp.from(member.createdAt()),
        Timestamp.from(member.updatedAt()));
    return member;
  }

  @Override
  public MemberRecord update(MemberRecord member) {
    jdbc.update(
        """
        UPDATE care_circle_member SET
          name = ?, age = ?, relationship = ?, avatar_emoji = ?, avatar_color = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        member.name(),
        member.age(),
        member.relationship(),
        member.avatarEmoji(),
        member.avatarColor(),
        Timestamp.from(member.updatedAt()),
        member.id());
    return member;
  }

  @Override
  public void softDelete(UUID memberId, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE care_circle_member SET deleted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        memberId);
  }

  private static MemberRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new MemberRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("name"),
        rs.getInt("age"),
        rs.getString("relationship"),
        rs.getString("avatar_emoji"),
        rs.getString("avatar_color"),
        rs.getBoolean("is_self"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
