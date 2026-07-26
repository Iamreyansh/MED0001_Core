package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.CustomerAddressStore.AddressRecord;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcCustomerAddressStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void listByCustomer_mapsRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    AddressRecord record = sample(Ids.newId(), Ids.newId(), true);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<AddressRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, false), 0));
            });

    assertThat(store.listByCustomer(record.customerId())).containsExactly(record);
  }

  @Test
  void countByCustomer_returnsCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    UUID customerId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(customerId))).thenReturn(3);

    assertThat(store.countByCustomer(customerId)).isEqualTo(3);
  }

  @Test
  void countByCustomer_null_returnsZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(null);

    assertThat(store.countByCustomer(Ids.newId())).isZero();
  }

  @Test
  void findByIdForCustomer_empty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    assertThat(store.findByIdForCustomer(Ids.newId(), Ids.newId())).isEmpty();
  }

  @Test
  void findByIdForCustomer_mapsDeletedAt() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    AddressRecord record =
        new AddressRecord(
            Ids.newId(),
            Ids.newId(),
            "HOME",
            "F",
            "A",
            "C",
            "S",
            "560066",
            BigDecimal.ONE,
            BigDecimal.TEN,
            false,
            NOW,
            NOW,
            NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id()), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<AddressRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResultSet(record, true), 0));
            });

    assertThat(store.findByIdForCustomer(record.id(), record.customerId())).contains(record);
  }

  @Test
  void insert_and_update_and_softDelete() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    AddressRecord record = sample(Ids.newId(), Ids.newId(), false);

    assertThat(store.insert(record)).isEqualTo(record);
    assertThat(store.update(record)).isEqualTo(record);
    store.softDelete(record.id(), NOW);
    store.clearDefaultFlags(record.customerId());
    store.setDefault(record.id(), record.customerId());
    store.setCustomerDefaultAddressId(record.customerId(), record.id());
    store.setCustomerDefaultAddressId(record.customerId(), null);

    verify(jdbc)
        .update(
            anyString(),
            eq(record.id()),
            eq(record.customerId()),
            eq(record.label()),
            eq(record.flatBuilding()),
            eq(record.areaLocality()),
            eq(record.city()),
            eq(record.state()),
            eq(record.pincode()),
            eq(record.latitude()),
            eq(record.longitude()),
            eq(record.isDefault()),
            eq(Timestamp.from(record.createdAt())),
            eq(Timestamp.from(record.updatedAt())));
  }

  @Test
  void findDefaultAddressId_presentAndEmpty() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerAddressStore store = new JdbcCustomerAddressStore(jdbc);
    UUID customerId = Ids.newId();
    UUID addressId = Ids.newId();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(customerId)))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(addressId);
              return List.of(mapper.mapRow(rs, 0));
            })
        .thenReturn(List.of());

    assertThat(store.findDefaultAddressId(customerId)).contains(addressId);
    assertThat(store.findDefaultAddressId(customerId)).isEmpty();
  }

  private static AddressRecord sample(UUID id, UUID customerId, boolean isDefault) {
    return new AddressRecord(
        id,
        customerId,
        "HOME",
        "Flat 1",
        "Area",
        "Bengaluru",
        "Karnataka",
        "560066",
        new BigDecimal("12.9693000"),
        new BigDecimal("77.7499000"),
        isDefault,
        NOW,
        NOW,
        null);
  }

  private static ResultSet mockResultSet(AddressRecord record, boolean withDeleted)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(record.id());
    when(rs.getObject("customer_id")).thenReturn(record.customerId());
    when(rs.getString("label")).thenReturn(record.label());
    when(rs.getString("flat_building")).thenReturn(record.flatBuilding());
    when(rs.getString("area_locality")).thenReturn(record.areaLocality());
    when(rs.getString("city")).thenReturn(record.city());
    when(rs.getString("state")).thenReturn(record.state());
    when(rs.getString("pincode")).thenReturn(record.pincode());
    when(rs.getBigDecimal("latitude")).thenReturn(record.latitude());
    when(rs.getBigDecimal("longitude")).thenReturn(record.longitude());
    when(rs.getBoolean("is_default")).thenReturn(record.isDefault());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(record.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(record.updatedAt()));
    when(rs.getTimestamp("deleted_at"))
        .thenReturn(
            withDeleted && record.deletedAt() != null ? Timestamp.from(record.deletedAt()) : null);
    return rs;
  }
}
