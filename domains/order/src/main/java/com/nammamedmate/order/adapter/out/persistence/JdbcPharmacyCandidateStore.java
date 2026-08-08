package com.nammamedmate.order.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.domain.Haversine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcPharmacyCandidateStore implements PharmacyCandidatePort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPharmacyCandidateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<PharmacyRow> findOpenNear(double lat, double lng, double radiusKm) {
    double latDelta = radiusKm / 111.0;
    double cosLat = Math.max(Math.abs(Math.cos(Math.toRadians(lat))), 1e-6);
    double lngDelta = radiusKm / (111.0 * cosLat);
    List<PharmacyRow> rows =
        jdbc.query(
            """
            SELECT p.id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS display_name,
                   p.city, p.logo_url, p.tagline, p.address::text AS address_json,
                   p.latitude, p.longitude, p.is_online, p.admin_forced_offline, p.status,
                   COALESCE(m.rating, 0) AS rating,
                   COALESCE(m.review_count, 0) AS review_count,
                   COALESCE(m.fill_rate_pct, 0) AS fill_rate_pct,
                   perf.avg_prep_minutes
            FROM pharmacies p
            LEFT JOIN pharmacy_directory_metrics m ON m.pharmacy_id = p.id
            LEFT JOIN pharmacy_performance_snapshot perf
              ON perf.pharmacy_id = p.id AND perf.period = '7D'::pharmacy_performance_period
            WHERE p.deleted_at IS NULL
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
              AND p.is_online = TRUE
              AND COALESCE(p.admin_forced_offline, FALSE) = FALSE
              AND p.status = 'ACTIVE'
              AND p.latitude BETWEEN ? AND ?
              AND p.longitude BETWEEN ? AND ?
            """,
            this::mapRow,
            lat - latDelta,
            lat + latDelta,
            lng - lngDelta,
            lng + lngDelta);
    List<PharmacyRow> near = new ArrayList<>();
    for (PharmacyRow row : rows) {
      if (!row.isOpen()) {
        continue;
      }
      if (row.latitude() == null || row.longitude() == null) {
        continue;
      }
      double d = Haversine.distanceKm(lat, lng, row.latitude(), row.longitude());
      if (d <= radiusKm) {
        near.add(row);
      }
    }
    return near;
  }

  @Override
  public Optional<PharmacyRow> findById(UUID pharmacyId) {
    List<PharmacyRow> rows =
        jdbc.query(
            """
            SELECT p.id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS display_name,
                   p.city, p.logo_url, p.tagline, p.address::text AS address_json,
                   p.latitude, p.longitude, p.is_online, p.admin_forced_offline, p.status,
                   COALESCE(m.rating, 0) AS rating,
                   COALESCE(m.review_count, 0) AS review_count,
                   COALESCE(m.fill_rate_pct, 0) AS fill_rate_pct,
                   perf.avg_prep_minutes
            FROM pharmacies p
            LEFT JOIN pharmacy_directory_metrics m ON m.pharmacy_id = p.id
            LEFT JOIN pharmacy_performance_snapshot perf
              ON perf.pharmacy_id = p.id AND perf.period = '7D'::pharmacy_performance_period
            WHERE p.id = ? AND p.deleted_at IS NULL
            """,
            this::mapRow,
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public List<String> categoriesAvailable(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT DISTINCT c.name
        FROM pharmacy_catalogue_mapping pcm
        JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
        JOIN medicine_category c ON c.id = mm.category_id
        WHERE pcm.pharmacy_id = ?
          AND pcm.is_visible = TRUE
          AND pcm.stock_quantity > 0
          AND mm.is_banned = FALSE
          AND c.deleted_at IS NULL
        ORDER BY c.name
        """,
        (rs, i) -> rs.getString(1),
        pharmacyId);
  }

  @Override
  public int visibleItemsCount(UUID pharmacyId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM pharmacy_catalogue_mapping pcm
            JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
            WHERE pcm.pharmacy_id = ?
              AND pcm.is_visible = TRUE
              AND pcm.stock_quantity > 0
              AND mm.is_banned = FALSE
            """,
            Integer.class,
            pharmacyId);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<String> openHoursSummary(UUID pharmacyId) {
    int dow = DayOfWeek.from(java.time.ZonedDateTime.now(IST)).getValue() % 7; // Sun=0
    List<String> rows =
        jdbc.query(
            """
            SELECT open_time, close_time, is_closed
            FROM pharmacy_operating_hours
            WHERE pharmacy_id = ? AND day_of_week = ?
            """,
            (rs, i) -> formatHours(rs),
            pharmacyId,
            dow);
    if (!rows.isEmpty() && rows.getFirst() != null) {
      return Optional.of(rows.getFirst());
    }
    List<String> any =
        jdbc.query(
            """
            SELECT open_time, close_time, is_closed
            FROM pharmacy_operating_hours
            WHERE pharmacy_id = ? AND is_closed = FALSE
            ORDER BY day_of_week
            LIMIT 1
            """,
            (rs, i) -> formatHours(rs),
            pharmacyId);
    return any.isEmpty() ? Optional.empty() : Optional.ofNullable(any.getFirst());
  }

  @Override
  public int refreshFillRatesFromDirectoryMetrics() {
    // ponytail: metrics table is source of truth; hot path JOINs it — no denormalised copy.
    Integer n =
        jdbc.queryForObject("SELECT COUNT(*) FROM pharmacy_directory_metrics", Integer.class);
    return n == null ? 0 : n;
  }

  private PharmacyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    String addressJson = rs.getString("address_json");
    String area = formatArea(addressJson, rs.getString("city"));
    String addressLine = formatAddressLine(addressJson);
    Double avgPrep = (Double) rs.getObject("avg_prep_minutes");
    if (rs.wasNull()) {
      avgPrep = null;
    }
    Number latObj = (Number) rs.getObject("latitude");
    Number lngObj = (Number) rs.getObject("longitude");
    return new PharmacyRow(
        (UUID) rs.getObject("id"),
        rs.getString("display_name"),
        area,
        addressLine,
        rs.getString("logo_url"),
        rs.getString("tagline"),
        latObj == null ? null : latObj.doubleValue(),
        lngObj == null ? null : lngObj.doubleValue(),
        rs.getBoolean("is_online"),
        rs.getBoolean("admin_forced_offline"),
        rs.getString("status"),
        rs.getBigDecimal("rating").doubleValue(),
        rs.getInt("review_count"),
        rs.getBigDecimal("fill_rate_pct").doubleValue(),
        avgPrep);
  }

  private String formatArea(String addressJson, String city) {
    String area = textField(addressJson, "area");
    String cityPart = city == null || city.isBlank() ? textField(addressJson, "city") : city.trim();
    if (area != null && !area.isBlank() && cityPart != null && !cityPart.isBlank()) {
      return area + ", " + cityPart;
    }
    if (area != null && !area.isBlank()) {
      return area;
    }
    return cityPart == null ? "" : cityPart;
  }

  private String formatAddressLine(String addressJson) {
    if (addressJson == null || addressJson.isBlank()) {
      return "";
    }
    String flat = textField(addressJson, "flat");
    String area = textField(addressJson, "area");
    String city = textField(addressJson, "city");
    StringBuilder sb = new StringBuilder();
    if (flat != null && !flat.isBlank()) {
      sb.append(flat.trim());
    }
    if (area != null && !area.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(area.trim());
    }
    if (city != null && !city.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(city.trim());
    }
    return sb.toString();
  }

  private String textField(String json, String field) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      JsonNode child = node.get(field);
      return child == null || child.isNull() ? null : child.asText();
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String formatHours(ResultSet rs) throws SQLException {
    if (rs.getBoolean("is_closed")) {
      return "Closed";
    }
    Time open = rs.getTime("open_time");
    Time close = rs.getTime("close_time");
    if (open == null || close == null) {
      return null;
    }
    LocalTime o = open.toLocalTime();
    LocalTime c = close.toLocalTime();
    return TIME_FMT.format(o) + " - " + TIME_FMT.format(c);
  }
}
