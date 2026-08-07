package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.CategoryStore.CategoryRow;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcCategoryStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcCategoryStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcCategoryStore(jdbc);
  }

  @Test
  void list_find_exists_nextOrder_insert_update_delete_reorder() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              UUID id = UUID.fromString("c0000001-0000-4000-8000-000000000001");
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("Antibiotics");
              when(rs.getString("slug")).thenReturn("antibiotics");
              when(rs.getString("icon_url")).thenReturn("https://cdn/x.svg");
              when(rs.getBoolean("is_visible")).thenReturn(true);
              when(rs.getInt("display_order")).thenReturn(1);
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              Instant now = Instant.parse("2026-08-08T00:00:00Z");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getInt("medicine_count")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("MAX(display_order)"), eq(Integer.class)))
        .thenReturn(5);

    assertThat(store.list(false, false)).hasSize(1);
    assertThat(store.list(true, true)).hasSize(1);
    assertThat(store.findById(UUID.randomUUID())).isPresent();
    assertThat(store.existsBySlug("antibiotics")).isTrue();
    assertThat(store.existsByName("Antibiotics")).isTrue();
    assertThat(store.existsByNameExcluding("Antibiotics", UUID.randomUUID())).isTrue();
    assertThat(store.nextDisplayOrder()).isEqualTo(6);

    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    CategoryRow row =
        new CategoryRow(
            UUID.randomUUID(), "N", "n", "https://cdn/n.svg", true, 1, null, now, now, 0);
    store.insert(row);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

    store.update(row.id(), "N2", "https://cdn/n2.svg", false, 2, now);
    store.update(row.id(), null, null, null, null, now);
    store.softDelete(row.id(), now);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
    assertThat(store.countExistingIds(List.of(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(2);
    assertThat(store.countExistingIds(List.of())).isZero();
    assertThat(store.countExistingIds(null)).isZero();

    store.reorder(List.of(new ReorderItem(row.id(), 3)), now);
    verify(jdbc).update(anyString(), eq(3), any(), eq(row.id()));
  }

  @Test
  void exists_falseWhenNullCount() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.existsBySlug("x")).isFalse();
    assertThat(store.existsByName("x")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(store.existsByNameExcluding("x", UUID.randomUUID())).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
    assertThat(store.nextDisplayOrder()).isEqualTo(1);
  }

  @Test
  void exists_falseWhenZeroCount() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(store.existsBySlug("x")).isFalse();
    assertThat(store.existsByName("x")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    assertThat(store.existsByNameExcluding("x", UUID.randomUUID())).isFalse();
  }

  @Test
  void mapRow_withDeletedAt() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              Instant now = Instant.parse("2026-08-08T00:00:00Z");
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("name")).thenReturn("X");
              when(rs.getString("slug")).thenReturn("x");
              when(rs.getString("icon_url")).thenReturn("https://cdn/x.svg");
              when(rs.getBoolean("is_visible")).thenReturn(false);
              when(rs.getInt("display_order")).thenReturn(1);
              when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              when(rs.getInt("medicine_count")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(UUID.randomUUID()).orElseThrow().deletedAt()).isNotNull();
  }

  @Test
  void countExistingIds_nullResult() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    assertThat(store.countExistingIds(List.of(UUID.randomUUID()))).isZero();
  }

  @Test
  void list_sqlIncludesFilters() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    store.list(false, false);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
    assertThat(sql.getValue()).contains("deleted_at IS NULL").contains("is_visible = TRUE");
  }
}
