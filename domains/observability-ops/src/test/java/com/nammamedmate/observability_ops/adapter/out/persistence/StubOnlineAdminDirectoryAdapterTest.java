package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class StubOnlineAdminDirectoryAdapterTest {

  @Test
  @SuppressWarnings("unchecked")
  void overrideAndJdbcDirectory() throws Exception {
    StubOnlineAdminDirectoryAdapter noJdbc = new StubOnlineAdminDirectoryAdapter();
    assertThat(noJdbc.onlineAdminIds(Set.of("admin_super"))).hasSize(1);
    UUID id = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    noJdbc.setOnline(List.of(id));
    assertThat(noJdbc.onlineAdminIds(Set.of())).containsExactly(id);

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StubOnlineAdminDirectoryAdapter live = new StubOnlineAdminDirectoryAdapter(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(live.onlineAdminIds(Set.of())).containsExactly(id);
    assertThat(live.onlineAdminIds(null)).containsExactly(id);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(live.onlineAdminIds(Set.of("admin_super", "admin_operations"))).containsExactly(id);
    Set<String> withNull = new HashSet<>();
    withNull.add(null);
    withNull.add(" ");
    assertThat(live.onlineAdminIds(withNull)).containsExactly(id);

    when(jdbc.query(anyString(), any(RowMapper.class))).thenThrow(new RuntimeException("down"));
    assertThat(live.onlineAdminIds(Set.of())).hasSize(1);
    live.setOnline(List.of());
    assertThat(live.onlineAdminIds(Set.of("admin_super"))).isEmpty();
  }
}
