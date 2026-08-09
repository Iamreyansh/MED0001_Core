package com.nammamedmate.pos.adapter.out.persistence;

import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.OfferAppliesTo;
import com.nammamedmate.pos.domain.OfferRedemption;
import com.nammamedmate.pos.domain.PharmacyOffer;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOfferStore implements OfferStore {

  private static final RowMapper<PharmacyOffer> OFFER_MAPPER = JdbcOfferStore::mapOffer;

  private final JdbcTemplate jdbc;

  public JdbcOfferStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PharmacyOffer insert(PharmacyOffer offer) {
    jdbc.update(
        """
        INSERT INTO pharmacy_offer (
          id, pharmacy_id, title, coupon_code, discount_type, discount_value,
          applies_to, scope_ids, is_online, is_counter, is_active,
          valid_from, valid_until, max_redemptions, total_redemptions,
          created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?::uuid[],?,?,?,?,?,?,?,?,?)
        """,
        offer.id(),
        offer.pharmacyId(),
        offer.title(),
        offer.couponCode(),
        offer.discountType().name(),
        offer.discountValue(),
        offer.appliesTo().name(),
        toUuidArrayLiteral(offer.scopeIds()),
        offer.online(),
        offer.counter(),
        offer.active(),
        offer.validFrom(),
        offer.validUntil(),
        offer.maxRedemptions(),
        offer.totalRedemptions(),
        Timestamp.from(offer.createdAt()),
        Timestamp.from(offer.updatedAt()));
    return offer;
  }

  @Override
  public Optional<PharmacyOffer> findById(UUID pharmacyId, UUID offerId) {
    List<PharmacyOffer> rows =
        jdbc.query(
            "SELECT * FROM pharmacy_offer WHERE pharmacy_id = ? AND id = ?",
            OFFER_MAPPER,
            pharmacyId,
            offerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PharmacyOffer> findByCoupon(UUID pharmacyId, String couponCode) {
    List<PharmacyOffer> rows =
        jdbc.query(
            "SELECT * FROM pharmacy_offer WHERE pharmacy_id = ? AND coupon_code = ?",
            OFFER_MAPPER,
            pharmacyId,
            couponCode);
    return rows.stream().findFirst();
  }

  @Override
  public boolean couponExists(UUID pharmacyId, String couponCode, UUID excludeOfferId) {
    Integer count;
    if (excludeOfferId == null) {
      count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pharmacy_offer WHERE pharmacy_id = ? AND coupon_code = ?",
              Integer.class,
              pharmacyId,
              couponCode);
    } else {
      count =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM pharmacy_offer
              WHERE pharmacy_id = ? AND coupon_code = ? AND id <> ?
              """,
              Integer.class,
              pharmacyId,
              couponCode,
              excludeOfferId);
    }
    if (count == null || count == 0) {
      return false;
    }
    return true;
  }

  @Override
  public PharmacyOffer update(PharmacyOffer offer) {
    jdbc.update(
        """
        UPDATE pharmacy_offer SET
          title=?, coupon_code=?, discount_type=?, discount_value=?,
          applies_to=?, scope_ids=?::uuid[], is_online=?, is_counter=?, is_active=?,
          valid_from=?, valid_until=?, max_redemptions=?, total_redemptions=?,
          updated_at=?
        WHERE id=? AND pharmacy_id=?
        """,
        offer.title(),
        offer.couponCode(),
        offer.discountType().name(),
        offer.discountValue(),
        offer.appliesTo().name(),
        toUuidArrayLiteral(offer.scopeIds()),
        offer.online(),
        offer.counter(),
        offer.active(),
        offer.validFrom(),
        offer.validUntil(),
        offer.maxRedemptions(),
        offer.totalRedemptions(),
        Timestamp.from(offer.updatedAt()),
        offer.id(),
        offer.pharmacyId());
    return offer;
  }

  @Override
  public void hardDelete(UUID pharmacyId, UUID offerId) {
    jdbc.update("DELETE FROM pharmacy_offer WHERE pharmacy_id = ? AND id = ?", pharmacyId, offerId);
  }

  @Override
  public ListPage list(UUID pharmacyId, String statusFilter, LocalDate today, int page, int limit) {
    String filter = statusFilter == null ? "ACTIVE" : statusFilter.toUpperCase();
    String where =
        switch (filter) {
          case "EXPIRED" -> " AND valid_until < ? ";
          case "ALL" -> " ";
          default -> " AND valid_until >= ? ";
        };
    boolean needsDate = !"ALL".equals(filter);
    int offset = (page - 1) * limit;

    String countSql = "SELECT COUNT(*) FROM pharmacy_offer WHERE pharmacy_id = ?" + where;
    Long total;
    if (needsDate) {
      total = jdbc.queryForObject(countSql, Long.class, pharmacyId, today);
    } else {
      total = jdbc.queryForObject(countSql, Long.class, pharmacyId);
    }

    String listSql =
        "SELECT * FROM pharmacy_offer WHERE pharmacy_id = ?"
            + where
            + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
    List<PharmacyOffer> items;
    if (needsDate) {
      items = jdbc.query(listSql, OFFER_MAPPER, pharmacyId, today, limit, offset);
    } else {
      items = jdbc.query(listSql, OFFER_MAPPER, pharmacyId, limit, offset);
    }
    return new ListPage(items, total == null ? 0L : total);
  }

  @Override
  public Kpi kpi(UUID pharmacyId, LocalDate today) {
    Integer active =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_offer
            WHERE pharmacy_id = ? AND is_active = TRUE AND valid_until >= ?
            """,
            Integer.class,
            pharmacyId,
            today);
    Long redemptions =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_redemptions), 0) FROM pharmacy_offer WHERE pharmacy_id = ?",
            Long.class,
            pharmacyId);
    return new Kpi(active == null ? 0 : active, redemptions == null ? 0L : redemptions);
  }

  @Override
  public List<PharmacyOffer> listActiveCounterOffers(UUID pharmacyId, LocalDate today) {
    return jdbc.query(
        """
        SELECT * FROM pharmacy_offer
        WHERE pharmacy_id = ?
          AND is_active = TRUE
          AND is_counter = TRUE
          AND valid_from <= ?
          AND valid_until >= ?
          AND (max_redemptions = 0 OR total_redemptions < max_redemptions)
        """,
        OFFER_MAPPER,
        pharmacyId,
        today,
        today);
  }

  @Override
  public Map<UUID, UUID> productCategoryIds(UUID pharmacyId, List<UUID> productIds) {
    if (productIds == null) {
      return Map.of();
    }
    if (productIds.isEmpty()) {
      return Map.of();
    }
    List<Map.Entry<UUID, UUID>> rows =
        jdbc.query(
            """
            SELECT id, category_id FROM pharmacy_product
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND id = ANY(?::uuid[])
            """,
            (rs, i) -> {
              UUID cat = (UUID) rs.getObject("category_id");
              if (cat == null) {
                return null;
              }
              return Map.entry((UUID) rs.getObject("id"), cat);
            },
            pharmacyId,
            toUuidArrayLiteral(productIds));
    Map<UUID, UUID> out = new HashMap<>();
    for (Map.Entry<UUID, UUID> e : rows) {
      if (e != null) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  @Override
  public Map<UUID, String> categoryNames(List<UUID> categoryIds) {
    if (categoryIds == null) {
      return Map.of();
    }
    if (categoryIds.isEmpty()) {
      return Map.of();
    }
    List<Map.Entry<UUID, String>> rows =
        jdbc.query(
            "SELECT id, name FROM medicine_category WHERE id = ANY(?::uuid[])",
            (rs, i) -> Map.entry((UUID) rs.getObject("id"), rs.getString("name")),
            toUuidArrayLiteral(categoryIds));
    Map<UUID, String> out = new HashMap<>();
    for (Map.Entry<UUID, String> e : rows) {
      out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  @Override
  public void insertRedemption(OfferRedemption redemption) {
    jdbc.update(
        """
        INSERT INTO offer_redemption (
          id, offer_id, pharmacy_id, invoice_id, customer_id,
          discount_amount_paise, channel, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        redemption.id(),
        redemption.offerId(),
        redemption.pharmacyId(),
        redemption.invoiceId(),
        redemption.customerId(),
        redemption.discountAmountPaise(),
        redemption.channel().name(),
        Timestamp.from(redemption.createdAt()));
  }

  @Override
  public void incrementRedemptions(UUID offerId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_offer
        SET total_redemptions = total_redemptions + 1, updated_at = ?
        WHERE id = ?
        """,
        Timestamp.from(updatedAt),
        offerId);
  }

  static PharmacyOffer mapOffer(ResultSet rs, int rowNum) throws SQLException {
    return new PharmacyOffer(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("title"),
        rs.getString("coupon_code"),
        DiscountType.valueOf(rs.getString("discount_type")),
        rs.getLong("discount_value"),
        OfferAppliesTo.valueOf(rs.getString("applies_to")),
        readUuidArray(rs, "scope_ids"),
        rs.getBoolean("is_online"),
        rs.getBoolean("is_counter"),
        rs.getBoolean("is_active"),
        rs.getObject("valid_from", LocalDate.class),
        rs.getObject("valid_until", LocalDate.class),
        rs.getInt("max_redemptions"),
        rs.getInt("total_redemptions"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  static List<UUID> readUuidArray(ResultSet rs, String col) throws SQLException {
    Array arr = rs.getArray(col);
    if (arr == null) {
      return List.of();
    }
    Object raw = arr.getArray();
    if (raw instanceof UUID[] uuids) {
      return Arrays.asList(uuids);
    }
    if (raw instanceof Object[] objs) {
      List<UUID> out = new ArrayList<>();
      for (Object o : objs) {
        if (o instanceof UUID u) {
          out.add(u);
        } else if (o != null) {
          out.add(UUID.fromString(o.toString()));
        }
      }
      return out;
    }
    return List.of();
  }

  static String toUuidArrayLiteral(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return "{}";
    }
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }
}
