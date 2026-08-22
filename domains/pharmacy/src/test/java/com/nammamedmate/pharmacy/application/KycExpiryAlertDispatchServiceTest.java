package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class KycExpiryAlertDispatchServiceTest {

  @Test
  @SuppressWarnings("unchecked")
  void dispatchDuePublishesAndMarksSent() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InMemoryOutboxStore store = new InMemoryOutboxStore();
    OutboxPublisher outbox = new OutboxPublisher(store, new ObjectMapper());
    Instant now = Instant.parse("2026-08-22T10:00:00Z");
    KycExpiryAlertDispatchService svc =
        new KycExpiryAlertDispatchService(jdbc, outbox, Clock.fixed(now, ZoneOffset.UTC));

    UUID alertId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(alertId);
              when(rs.getObject("document_id")).thenReturn(docId);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
              when(rs.getString("template")).thenReturn("DRUG_LICENCE_30D");
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.update(anyString(), any(), eq(alertId))).thenReturn(1);

    assertThat(svc.dispatchDue()).isEqualTo(1);
    assertThat(store.all()).isNotEmpty();
    verify(jdbc).update(anyString(), any(), eq(alertId));
  }
}
