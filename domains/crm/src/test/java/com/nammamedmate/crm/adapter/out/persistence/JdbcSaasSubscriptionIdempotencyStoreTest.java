package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasSubscriptionIdempotencyStore.CachedResponse;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcSaasSubscriptionIdempotencyStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void findAndInsert() throws Exception {
    JdbcSaasSubscriptionIdempotencyStore store = new JdbcSaasSubscriptionIdempotencyStore(jdbc);
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getString("idempotency_key")).thenReturn("k1");
    when(rs.getObject("account_id")).thenReturn(accountId);
    when(rs.getString("operation")).thenReturn("SUBSCRIBE");
    when(rs.getString("response_json")).thenReturn("{\"ok\":true}");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<CachedResponse> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    Optional<CachedResponse> found = store.findByKey("k1");
    assertThat(found).isPresent();
    assertThat(found.get().operation()).isEqualTo("SUBSCRIBE");
    assertThat(found.get().responseJson()).contains("ok");

    store.insert("k1", accountId, "SUBSCRIBE", "{\"ok\":true}", now);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(Timestamp.class));
  }
}
