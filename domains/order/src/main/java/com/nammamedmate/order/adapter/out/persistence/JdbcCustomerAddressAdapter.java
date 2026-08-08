package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcCustomerAddressAdapter implements CustomerAddressPort {

  private final JdbcTemplate jdbc;

  public JdbcCustomerAddressAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AddressRow> findForCustomer(UUID addressId, UUID customerId) {
    List<AddressRow> rows =
        jdbc.query(
            """
            SELECT id, customer_id, label, flat_building, area_locality, city, state, pincode,
                   latitude, longitude
            FROM customer_addresses
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new AddressRow(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("label"),
                    formatFull(
                        rs.getString("flat_building"),
                        rs.getString("area_locality"),
                        rs.getString("city"),
                        rs.getString("pincode")),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude")),
            addressId,
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<AddressRow> findDefault(UUID customerId) {
    List<AddressRow> rows =
        jdbc.query(
            """
            SELECT id, customer_id, label, flat_building, area_locality, city, state, pincode,
                   latitude, longitude
            FROM customer_addresses
            WHERE customer_id = ? AND is_default = TRUE AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) ->
                new AddressRow(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("label"),
                    formatFull(
                        rs.getString("flat_building"),
                        rs.getString("area_locality"),
                        rs.getString("city"),
                        rs.getString("pincode")),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude")),
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private static String formatFull(String flat, String area, String city, String pincode) {
    return flat + ", " + area + ", " + city + " " + pincode;
  }
}
