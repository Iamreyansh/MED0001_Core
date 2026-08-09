package com.nammamedmate.integration.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.GovernmentVerificationCacheStore;
import com.nammamedmate.integration.domain.GovernmentVerificationCacheEntry;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcGovernmentVerificationCacheStore implements GovernmentVerificationCacheStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcGovernmentVerificationCacheStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public Optional<GovernmentVerificationCacheEntry> findValid(
      String verificationType, String identifier, String state, Instant now) {
    String stateKey = state == null ? "" : state;
    return jdbc
        .query(
            """
            SELECT id, verification_type, identifier, state, result_json::text AS result_json,
                   is_valid, expiry_date, verified_at, expires_at
              FROM government_verification_cache
             WHERE verification_type = ?
               AND identifier = ?
               AND state = ?
               AND expires_at > ?
             LIMIT 1
            """,
            (rs, rowNum) ->
                new GovernmentVerificationCacheEntry(
                    (UUID) rs.getObject("id"),
                    rs.getString("verification_type"),
                    rs.getString("identifier"),
                    emptyToNull(rs.getString("state")),
                    readJson(rs.getString("result_json")),
                    rs.getBoolean("is_valid"),
                    rs.getDate("expiry_date") == null
                        ? null
                        : rs.getDate("expiry_date").toLocalDate(),
                    rs.getTimestamp("verified_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()),
            verificationType,
            identifier,
            stateKey,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  @Override
  public void upsert(GovernmentVerificationCacheEntry entry) {
    String stateKey = entry.state() == null ? "" : entry.state();
    jdbc.update(
        """
        INSERT INTO government_verification_cache (
          id, verification_type, identifier, state, result_json, is_valid,
          expiry_date, verified_at, expires_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        ON CONFLICT (verification_type, identifier, state)
        DO UPDATE SET
          id = EXCLUDED.id,
          result_json = EXCLUDED.result_json,
          is_valid = EXCLUDED.is_valid,
          expiry_date = EXCLUDED.expiry_date,
          verified_at = EXCLUDED.verified_at,
          expires_at = EXCLUDED.expires_at
        """,
        entry.id(),
        entry.verificationType(),
        entry.identifier(),
        stateKey,
        toJson(entry.resultJson()),
        entry.valid(),
        entry.expiryDate() == null ? null : Date.valueOf(entry.expiryDate()),
        Timestamp.from(entry.verifiedAt()),
        Timestamp.from(entry.expiresAt()));
  }

  private String toJson(Map<String, Object> map) {
    try {
      return mapper.writeValueAsString(map);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialise verification cache JSON", e);
    }
  }

  private Map<String, Object> readJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return Map.of();
      }
      return mapper.readValue(raw, MAP);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse verification cache JSON", e);
    }
  }

  private static String emptyToNull(String state) {
    return (state == null || state.isEmpty()) ? null : state;
  }
}
