package com.nammamedmate.api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition-root: POS pharmacy detail ← pharmacies table (no domain→domain dep). */
@Configuration
public class PosPharmacyBridgeConfig {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Bean
  @Primary
  PosPharmacyPort posPharmacyPort(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return pharmacyId -> {
      List<PosPharmacyPort.PharmacyInfo> rows =
          jdbc.query(
              """
              SELECT COALESCE(business_name, name) AS display_name,
                     address, phone, gstin, drug_licence_number
              FROM pharmacies
              WHERE id = ? AND deleted_at IS NULL
              LIMIT 1
              """,
              (rs, i) ->
                  new PosPharmacyPort.PharmacyInfo(
                      rs.getString("display_name"),
                      formatAddress(rs.getString("address"), objectMapper),
                      rs.getString("phone"),
                      rs.getString("gstin"),
                      rs.getString("drug_licence_number")),
              pharmacyId);
      return rows.stream().findFirst();
    };
  }

  static String formatAddress(String json, ObjectMapper mapper) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      Map<String, Object> address = mapper.readValue(json, MAP_TYPE);
      List<String> parts = new ArrayList<>();
      for (String key : List.of("line1", "line2", "street", "area", "city", "state", "pincode")) {
        Object v = address.get(key);
        if (v != null && !String.valueOf(v).isBlank()) {
          parts.add(String.valueOf(v).trim());
        }
      }
      if (parts.isEmpty()) {
        return json;
      }
      return String.join(", ", parts);
    } catch (JsonProcessingException e) {
      return json;
    }
  }
}
