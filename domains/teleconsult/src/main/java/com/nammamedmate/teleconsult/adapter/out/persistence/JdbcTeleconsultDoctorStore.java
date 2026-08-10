package com.nammamedmate.teleconsult.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcTeleconsultDoctorStore implements TeleconsultDoctorStore {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcTeleconsultDoctorStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(TeleconsultDoctor d) {
    jdbc.update(
        """
        INSERT INTO teleconsult_doctors (
          id, name, qualification, registration_no, specialty, languages_spoken,
          years_experience, avatar_url, bio, internal_phone, is_available,
          avg_rating, total_consults, consults_today, last_assigned_at,
          created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?::jsonb,
          ?, ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?
        )
        """,
        d.id(),
        d.name(),
        d.qualification(),
        d.registrationNo(),
        d.specialty(),
        toJson(d.languagesSpoken()),
        d.yearsExperience(),
        d.avatarUrl(),
        d.bio(),
        d.internalPhoneCiphertext(),
        d.available(),
        d.avgRating(),
        d.totalConsults(),
        d.consultsToday(),
        ts(d.lastAssignedAt()),
        Timestamp.from(d.createdAt()),
        Timestamp.from(d.updatedAt()),
        ts(d.deletedAt()));
  }

  @Override
  public void update(TeleconsultDoctor d) {
    jdbc.update(
        """
        UPDATE teleconsult_doctors SET
          name = ?, qualification = ?, registration_no = ?, specialty = ?,
          languages_spoken = ?::jsonb, years_experience = ?, avatar_url = ?, bio = ?,
          internal_phone = ?, is_available = ?, avg_rating = ?, total_consults = ?,
          consults_today = ?, last_assigned_at = ?, updated_at = ?, deleted_at = ?
        WHERE id = ?
        """,
        d.name(),
        d.qualification(),
        d.registrationNo(),
        d.specialty(),
        toJson(d.languagesSpoken()),
        d.yearsExperience(),
        d.avatarUrl(),
        d.bio(),
        d.internalPhoneCiphertext(),
        d.available(),
        d.avgRating(),
        d.totalConsults(),
        d.consultsToday(),
        ts(d.lastAssignedAt()),
        Timestamp.from(d.updatedAt()),
        ts(d.deletedAt()),
        d.id());
  }

  @Override
  public Optional<TeleconsultDoctor> findById(UUID id) {
    List<TeleconsultDoctor> rows =
        jdbc.query(
            "SELECT * FROM teleconsult_doctors WHERE id = ? AND deleted_at IS NULL",
            this::mapRow,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<TeleconsultDoctor> findByRegistrationNo(String registrationNo) {
    if (registrationNo == null || registrationNo.isBlank()) {
      return Optional.empty();
    }
    List<TeleconsultDoctor> rows =
        jdbc.query(
            """
            SELECT * FROM teleconsult_doctors
            WHERE registration_no = ? AND deleted_at IS NULL
            """,
            this::mapRow,
            registrationNo.trim());
    return rows.stream().findFirst();
  }

  @Override
  public Page list(ListFilter filter) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE deleted_at IS NULL ");
    if (filter.available() != null) {
      where.append(" AND is_available = ? ");
      args.add(filter.available());
    }
    if (filter.specialty() != null && !filter.specialty().isBlank()) {
      where.append(" AND specialty ILIKE ? ");
      args.add(filter.specialty().trim());
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM teleconsult_doctors" + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;
    int page = Math.max(filter.page(), 1);
    int limit = Math.min(Math.max(filter.limit(), 1), 100);
    int offset = (page - 1) * limit;
    args.add(limit);
    args.add(offset);
    List<TeleconsultDoctor> items =
        jdbc.query(
            """
            SELECT * FROM teleconsult_doctors
            """
                + where
                + """
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """,
            this::mapRow,
            args.toArray());
    return new Page(items, totalCount);
  }

  @Override
  public int resetConsultsToday() {
    return jdbc.update(
        """
        UPDATE teleconsult_doctors
        SET consults_today = 0, updated_at = NOW()
        WHERE deleted_at IS NULL AND consults_today <> 0
        """);
  }

  @Override
  public List<TeleconsultDoctor> listAvailable() {
    return jdbc.query(
        """
        SELECT * FROM teleconsult_doctors
        WHERE deleted_at IS NULL AND is_available = TRUE
        ORDER BY last_assigned_at ASC NULLS FIRST, id ASC
        """,
        this::mapRow);
  }

  private TeleconsultDoctor mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new TeleconsultDoctor(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("qualification"),
        rs.getString("registration_no"),
        rs.getString("specialty"),
        parseLanguages(rs.getString("languages_spoken")),
        rs.getInt("years_experience"),
        rs.getString("avatar_url"),
        rs.getString("bio"),
        rs.getString("internal_phone"),
        rs.getBoolean("is_available"),
        rs.getBigDecimal("avg_rating"),
        rs.getInt("total_consults"),
        rs.getInt("consults_today"),
        instant(rs.getTimestamp("last_assigned_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("deleted_at")));
  }

  private List<String> parseLanguages(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> list = objectMapper.readValue(json, STRING_LIST);
      return list == null ? List.of() : list;
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid languages_spoken JSON", ex);
    }
  }

  private String toJson(List<String> languages) {
    try {
      return objectMapper.writeValueAsString(languages);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize languages_spoken", ex);
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
