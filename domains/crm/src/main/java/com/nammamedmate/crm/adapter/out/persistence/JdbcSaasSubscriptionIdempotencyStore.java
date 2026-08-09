package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasSubscriptionIdempotencyStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasSubscriptionIdempotencyStore implements SaasSubscriptionIdempotencyStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasSubscriptionIdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<CachedResponse> MAPPER =
      (rs, i) ->
          new CachedResponse(
              rs.getString("idempotency_key"),
              (UUID) rs.getObject("account_id"),
              rs.getString("operation"),
              rs.getString("response_json"));

  @Override
  public Optional<CachedResponse> findByKey(String idempotencyKey) {
    List<CachedResponse> rows =
        jdbc.query(
            """
            SELECT idempotency_key, account_id, operation, response_json
            FROM crm_subscription_idempotency
            WHERE idempotency_key = ?
            """,
            MAPPER,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(
      String idempotencyKey,
      UUID accountId,
      String operation,
      String responseJson,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO crm_subscription_idempotency (
          idempotency_key, account_id, operation, response_json, created_at
        ) VALUES (?, ?, ?, ?::jsonb, ?)
        """,
        idempotencyKey,
        accountId,
        operation,
        responseJson,
        Timestamp.from(createdAt));
  }
}
