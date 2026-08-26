package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.PriceCeilingPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPriceCeilingAdapterTest {

  @Test
  @SuppressWarnings("unchecked")
  void rejectsOverCeilingAndSkipsNulls() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPriceCeilingAdapter adapter = new JdbcPriceCeilingAdapter(jdbc);
    UUID med = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenReturn(List.of(new Object[] {"Amox", 7200L}));
    // row mapper is invoked by real jdbc; stub the mapped result instead
    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getString("name")).thenReturn("Amox");
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(7200L);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThatThrownBy(
            () ->
                adapter.assertWithinCeiling(
                    UUID.randomUUID(), List.of(new PriceCeilingPort.Line(med, 8000L))))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRICE_CEILING_VIOLATED");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med))).thenReturn(List.of());
    assertThatCode(
            () ->
                adapter.assertWithinCeiling(
                    UUID.randomUUID(), List.of(new PriceCeilingPort.Line(med, 8000L))))
        .doesNotThrowAnyException();
    adapter.assertWithinCeiling(UUID.randomUUID(), null);
    adapter.assertWithinCeiling(
        UUID.randomUUID(), java.util.Arrays.asList((PriceCeilingPort.Line) null));
    adapter.assertWithinCeiling(UUID.randomUUID(), List.of(new PriceCeilingPort.Line(null, 8000L)));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getString("name")).thenReturn(null);
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(null);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    adapter.assertWithinCeiling(UUID.randomUUID(), List.of(new PriceCeilingPort.Line(med, 8000L)));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getString("name")).thenReturn(null);
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(7200L);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    adapter.assertWithinCeiling(UUID.randomUUID(), List.of(new PriceCeilingPort.Line(med, 7200L)));
    assertThatThrownBy(
            () ->
                adapter.assertWithinCeiling(
                    UUID.randomUUID(), List.of(new PriceCeilingPort.Line(med, 7201L))))
        .isInstanceOf(AppException.class);
  }
}
