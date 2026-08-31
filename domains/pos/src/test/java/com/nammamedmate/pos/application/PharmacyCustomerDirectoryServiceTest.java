package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyCustomerDirectoryServiceTest {

  private JdbcTemplate jdbc;
  private PharmacyCustomerDirectoryService service;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service = new PharmacyCustomerDirectoryService(jdbc);
  }

  @Test
  void listsCustomersAndGuards() throws Exception {
    UUID cid = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Map<String, Object>> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("customer_id")).thenReturn(cid);
              when(rs.getString("customer_name")).thenReturn("Priya");
              when(rs.getString("customer_phone")).thenReturn("+919876543210");
              when(rs.getTimestamp("last_purchase_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-08-31T06:00:00Z")));
              when(rs.getLong("invoices")).thenReturn(3L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(service.list(owner, "pri", 1, 20).meta().total()).isEqualTo(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Map<String, Object>> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("customer_id")).thenReturn(null);
              when(rs.getString("customer_name")).thenReturn(null);
              when(rs.getString("customer_phone")).thenReturn(null);
              when(rs.getTimestamp("last_purchase_at")).thenReturn(null);
              when(rs.getLong("invoices")).thenReturn(0L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(service.list(owner, "  ", null, null).meta().total()).isZero();
    assertThat(service.list(owner, null, 1, 20).meta().total()).isZero();
    assertThatThrownBy(() -> service.list(null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noShop, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }
}
