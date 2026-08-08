package com.nammamedmate.settings.adapter.out.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class JdbcAdminAuditAppendAdapterTest {

  @Test
  void appendWritesAndSwallowsFailures() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenReturn("Ayesha Siddiqui");
    JdbcAdminAuditAppendAdapter adapter = new JdbcAdminAuditAppendAdapter(jdbc, new ObjectMapper());
    adapter.append(
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "staff.role_changed",
        Map.of("role", "admin_support"),
        Map.of("role", "admin_operations"));
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

    adapter.append(
        Ids.newId(),
        null,
        Ids.newId(),
        "staff.status_changed",
        Map.of("status", "ACTIVE"),
        Map.of("status", "SUSPENDED"));

    adapter.append(
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "staff.name_changed",
        Map.of("name", "A"),
        Map.of("name", "B"));

    adapter.append(Ids.newId(), "admin_super", Ids.newId(), "staff.invited", null, null);

    adapter.append(
        "feature_flag",
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "feature_flag.updated",
        Map.of("enabled", true),
        Map.of("enabled", false));

    adapter.append(
        "  ", Ids.newId(), "admin_super", Ids.newId(), "x", Map.of("other", 1), Map.of("other", 2));

    adapter.append(
        null, Ids.newId(), "admin_super", Ids.newId(), "y", Map.of("other", 1), Map.of("other", 2));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> extractor = inv.getArgument(1);
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getString(1)).thenReturn("FromRs");
              return extractor.extractData(rs);
            });
    adapter.append(
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "staff.from-rs",
        Map.of("password", "secret"),
        Map.of("ok", 1));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> extractor = inv.getArgument(1);
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.next()).thenReturn(false);
              return extractor.extractData(rs);
            });
    adapter.append(
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "staff.x",
        Map.of("password", "secret"),
        Map.of("ok", 1));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenReturn("  ");
    adapter.append(
        Ids.newId(),
        "admin_super",
        Ids.newId(),
        "staff.blank-name",
        Map.of("a", 1),
        Map.of("b", 2));

    doThrow(new RuntimeException("name lookup"))
        .when(jdbc)
        .query(anyString(), any(ResultSetExtractor.class), any());
    adapter.append(
        Ids.newId(), "admin_super", Ids.newId(), "staff.y", Map.of("a", 1), Map.of("b", 2));

    doThrow(new RuntimeException("db down"))
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
        Ids.newId(), "admin_super", Ids.newId(), "staff.deleted", Map.of("x", 1), Map.of("y", 2));

    adapter.append(
        null, null, null, Ids.newId(), "z", Map.of("enabled", true), Map.of("enabled", false));
  }
}
