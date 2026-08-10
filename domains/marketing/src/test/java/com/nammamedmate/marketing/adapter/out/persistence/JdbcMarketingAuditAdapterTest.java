package com.nammamedmate.marketing.adapter.out.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcMarketingAuditAdapterTest {

  @Mock JdbcTemplate jdbc;

  @Test
  void appendSuccessAndFailure() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JdbcMarketingAuditAdapter adapter = new JdbcMarketingAuditAdapter(jdbc, mapper);
    UUID actor = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    adapter.append("banner", actor, "ADMIN_SUPER", entity, "CREATE", null, Map.of("ok", true));
    adapter.append(" ", actor, null, entity, "UPDATE", Map.of("a", 1), Map.of("b", 2));
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), any(Object[].class));

    doThrow(new RuntimeException("db")).when(jdbc).update(anyString(), any(Object[].class));
    adapter.append("banner", actor, "ADMIN_SUPER", entity, "DELETE", Map.of(), Map.of());
  }
}
