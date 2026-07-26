package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPincodeReferenceStore implements PincodeReferenceStore {

  private final JdbcTemplate jdbc;

  public JdbcPincodeReferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PincodeRecord> findServiceable(String pincode) {
    List<PincodeRecord> rows =
        jdbc.query(
            """
            SELECT pincode, state_code, state_name, serviceable
            FROM pincode_reference
            WHERE pincode = ? AND serviceable = TRUE
            """,
            (rs, n) ->
                new PincodeRecord(
                    rs.getString("pincode"),
                    rs.getString("state_code"),
                    rs.getString("state_name"),
                    rs.getBoolean("serviceable")),
            pincode);
    return rows.stream().findFirst();
  }
}
