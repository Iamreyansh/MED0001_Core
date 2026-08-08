package com.nammamedmate.rider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRiderSessionRevokeAdapterTest {

  @Test
  void revokeAllForUserUpdatesSessions() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);
    JdbcRiderSessionRevokeAdapter adapter = new JdbcRiderSessionRevokeAdapter(jdbc);
    UUID userId = Ids.newId();
    Instant at = Instant.parse("2026-08-08T10:00:00Z");
    assertThat(adapter.revokeAllForUser(userId, at)).isEqualTo(2);
  }
}
