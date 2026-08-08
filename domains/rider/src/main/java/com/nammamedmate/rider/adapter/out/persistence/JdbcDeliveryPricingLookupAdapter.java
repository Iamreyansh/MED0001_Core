package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryPricingLookupAdapter implements DeliveryPricingLookupPort {

  private final JdbcTemplate jdbc;

  public JdbcDeliveryPricingLookupAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PharmacyGeo> findPharmacy(UUID pharmacyId) {
    if (pharmacyId == null) {
      return Optional.empty();
    }
    List<PharmacyGeo> rows =
        jdbc.query(
            """
            SELECT id, name, latitude, longitude
            FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> {
              Object latObj = rs.getObject("latitude");
              Object lngObj = rs.getObject("longitude");
              if (latObj == null || lngObj == null) {
                return null;
              }
              double lat =
                  latObj instanceof Number n
                      ? n.doubleValue()
                      : Double.parseDouble(latObj.toString());
              double lng =
                  lngObj instanceof Number n
                      ? n.doubleValue()
                      : Double.parseDouble(lngObj.toString());
              return new PharmacyGeo((UUID) rs.getObject("id"), rs.getString("name"), lat, lng);
            },
            pharmacyId);
    return rows.stream().filter(r -> r != null).findFirst();
  }

  @Override
  public Optional<AddressGeo> findAddress(UUID addressId) {
    if (addressId == null) {
      return Optional.empty();
    }
    List<AddressGeo> rows =
        jdbc.query(
            """
            SELECT id, latitude, longitude
            FROM customer_addresses
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new AddressGeo(
                    (UUID) rs.getObject("id"), rs.getDouble("latitude"), rs.getDouble("longitude")),
            addressId);
    return rows.stream().findFirst();
  }
}
