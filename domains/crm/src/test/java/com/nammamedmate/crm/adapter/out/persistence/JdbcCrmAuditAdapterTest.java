package com.nammamedmate.crm.adapter.out.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcCrmAuditAdapterTest {

  @Mock JdbcTemplate jdbc;

  @Test
  void appendWritesAndSwallowsFailures() {
    JdbcCrmAuditAdapter adapter =
        new JdbcCrmAuditAdapter(jdbc, new ObjectMapper().findAndRegisterModules());
    UUID entity = Ids.newId();
    adapter.append(
        "saas_plan",
        Ids.newId(),
        "admin_super",
        entity,
        "saas_plan.updated",
        Map.of("price_monthly_rs", 699),
        Map.of("price_monthly_rs", 799));
    verify(jdbc)
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());

    adapter.append(null, null, null, entity, "x", null, null);
    adapter.append("  ", Ids.newId(), null, entity, "y", Map.of(), Map.of());

    doThrow(new RuntimeException("db"))
        .when(jdbc)
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    adapter.append(
        "saas_plan", Ids.newId(), "admin_super", entity, "fail", Map.of("a", 1), Map.of("b", 2));
  }
}
