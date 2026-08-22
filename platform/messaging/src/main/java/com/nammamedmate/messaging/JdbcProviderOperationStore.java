package com.nammamedmate.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcProviderOperationStore implements ProviderOperationStore {

  private static final RowMapper<Operation> MAPPER =
      (rs, rowNum) ->
          new Operation(
              rs.getString("operation_type"),
              rs.getString("idempotency_key"),
              rs.getString("provider_ref"),
              rs.getString("status"));

  private final JdbcTemplate jdbc;

  public JdbcProviderOperationStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Operation ensurePending(String operationType, String idempotencyKey, String provider) {
    Optional<Operation> existing = find(operationType, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    try {
      jdbc.update(
          """
          INSERT INTO provider_operation
            (id, operation_type, idempotency_key, provider, status, created_at, updated_at)
          VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
          """,
          UUID.randomUUID(),
          operationType,
          idempotencyKey,
          provider,
          Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()));
    } catch (DuplicateKeyException ignored) {
      // concurrent claim
    }
    return find(operationType, idempotencyKey).orElseThrow();
  }

  @Override
  public Optional<Operation> find(String operationType, String idempotencyKey) {
    List<Operation> rows =
        jdbc.query(
            """
            SELECT operation_type, idempotency_key, provider_ref, status
              FROM provider_operation
             WHERE operation_type = ? AND idempotency_key = ?
            """,
            MAPPER,
            operationType,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public void markSent(String operationType, String idempotencyKey, String providerRef) {
    update(operationType, idempotencyKey, "SENT", providerRef, null);
  }

  @Override
  public void markSucceeded(String operationType, String idempotencyKey, String providerRef) {
    update(operationType, idempotencyKey, "SUCCEEDED", providerRef, null);
  }

  @Override
  public void markFailed(String operationType, String idempotencyKey, String error) {
    update(operationType, idempotencyKey, "FAILED", null, error);
  }

  @Override
  public void markAmbiguous(
      String operationType, String idempotencyKey, String providerRef, String error) {
    update(operationType, idempotencyKey, "AMBIGUOUS", providerRef, error);
  }

  private void update(
      String operationType, String idempotencyKey, String status, String providerRef, String error) {
    jdbc.update(
        """
        UPDATE provider_operation
           SET status = ?, provider_ref = COALESCE(?, provider_ref), last_error = ?, updated_at = ?
         WHERE operation_type = ? AND idempotency_key = ?
        """,
        status,
        providerRef,
        error,
        Timestamp.from(Instant.now()),
        operationType,
        idempotencyKey);
  }
}
