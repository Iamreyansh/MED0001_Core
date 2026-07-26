package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.CustomerAddressStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcCustomerAddressStore implements CustomerAddressStore {

  private static final RowMapper<AddressRecord> ROW = JdbcCustomerAddressStore::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcCustomerAddressStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AddressRecord> listByCustomer(UUID customerId) {
    return jdbc.query(
        """
        SELECT * FROM customer_addresses
        WHERE customer_id = ? AND deleted_at IS NULL
        ORDER BY is_default DESC, created_at ASC
        """,
        ROW,
        customerId);
  }

  @Override
  public int countByCustomer(UUID customerId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM customer_addresses
            WHERE customer_id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            customerId);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<AddressRecord> findByIdForCustomer(UUID addressId, UUID customerId) {
    List<AddressRecord> rows =
        jdbc.query(
            """
            SELECT * FROM customer_addresses
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            ROW,
            addressId,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public AddressRecord insert(AddressRecord address) {
    jdbc.update(
        """
        INSERT INTO customer_addresses (
          id, customer_id, label, flat_building, area_locality, city, state, pincode,
          latitude, longitude, is_default, created_at, updated_at, deleted_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        address.id(),
        address.customerId(),
        address.label(),
        address.flatBuilding(),
        address.areaLocality(),
        address.city(),
        address.state(),
        address.pincode(),
        address.latitude(),
        address.longitude(),
        address.isDefault(),
        Timestamp.from(address.createdAt()),
        Timestamp.from(address.updatedAt()));
    return address;
  }

  @Override
  public AddressRecord update(AddressRecord address) {
    jdbc.update(
        """
        UPDATE customer_addresses SET
          label = ?, flat_building = ?, area_locality = ?, city = ?, state = ?, pincode = ?,
          latitude = ?, longitude = ?, updated_at = ?
        WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
        """,
        address.label(),
        address.flatBuilding(),
        address.areaLocality(),
        address.city(),
        address.state(),
        address.pincode(),
        address.latitude(),
        address.longitude(),
        Timestamp.from(address.updatedAt()),
        address.id(),
        address.customerId());
    return address;
  }

  @Override
  public void softDelete(UUID addressId, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE customer_addresses SET deleted_at = ?, updated_at = ?, is_default = FALSE
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        addressId);
  }

  @Override
  public void clearDefaultFlags(UUID customerId) {
    jdbc.update(
        """
        UPDATE customer_addresses SET is_default = FALSE, updated_at = NOW()
        WHERE customer_id = ? AND deleted_at IS NULL AND is_default = TRUE
        """,
        customerId);
  }

  @Override
  public void setDefault(UUID addressId, UUID customerId) {
    jdbc.update(
        """
        UPDATE customer_addresses SET is_default = TRUE, updated_at = NOW()
        WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
        """,
        addressId,
        customerId);
  }

  @Override
  public void setCustomerDefaultAddressId(UUID customerId, UUID addressIdOrNull) {
    jdbc.update(
        """
        UPDATE customers SET default_address_id = ?, updated_at = NOW()
        WHERE id = ? AND deleted_at IS NULL
        """,
        addressIdOrNull,
        customerId);
  }

  @Override
  public Optional<UUID> findDefaultAddressId(UUID customerId) {
    List<UUID> rows =
        jdbc.query(
            """
            SELECT id FROM customer_addresses
            WHERE customer_id = ? AND is_default = TRUE AND deleted_at IS NULL
            """,
            (rs, n) -> (UUID) rs.getObject("id"),
            customerId);
    return rows.stream().findFirst();
  }

  private static AddressRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new AddressRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("label"),
        rs.getString("flat_building"),
        rs.getString("area_locality"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("pincode"),
        rs.getBigDecimal("latitude"),
        rs.getBigDecimal("longitude"),
        rs.getBoolean("is_default"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
