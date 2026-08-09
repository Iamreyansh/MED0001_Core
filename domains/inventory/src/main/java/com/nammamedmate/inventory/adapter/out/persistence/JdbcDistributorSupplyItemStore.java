package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.domain.DistributorFormats;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDistributorSupplyItemStore implements DistributorSupplyItemStore {

  private final JdbcTemplate jdbc;

  public JdbcDistributorSupplyItemStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertFromGrn(
      UUID pharmacyId,
      UUID distributorId,
      UUID productId,
      long purchasePricePaise,
      String schemeDescription,
      Instant purchasedAt) {
    jdbc.update(
        """
        INSERT INTO distributor_supply_item (
          id, distributor_id, product_id, pharmacy_id, purchase_price_paise,
          scheme_description, is_preferred_source, last_purchased_at, updated_at
        ) VALUES (?,?,?,?,?,?,FALSE,?,?)
        ON CONFLICT (distributor_id, product_id) DO UPDATE SET
          purchase_price_paise = EXCLUDED.purchase_price_paise,
          scheme_description = EXCLUDED.scheme_description,
          last_purchased_at = EXCLUDED.last_purchased_at,
          updated_at = EXCLUDED.updated_at
        """,
        UUID.randomUUID(),
        distributorId,
        productId,
        pharmacyId,
        purchasePricePaise,
        schemeDescription,
        Timestamp.from(purchasedAt),
        Timestamp.from(purchasedAt));
  }

  @Override
  public ListResult listByDistributor(
      UUID pharmacyId, UUID distributorId, String q, int page, int limit) {
    StringBuilder where =
        new StringBuilder(
            """
            WHERE s.pharmacy_id = ? AND s.distributor_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    args.add(distributorId);
    if (q != null && !q.isBlank()) {
      where.append(" AND p.name ILIKE ?");
      args.add("%" + q.trim() + "%");
    }
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM distributor_supply_item s
              JOIN pharmacy_product p ON p.id = s.product_id
            """
                + where,
            Long.class,
            args.toArray());
    long count = total == null ? 0L : total;
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<RawSupply> raw =
        jdbc.query(
            """
            SELECT s.product_id, p.name AS product_name, p.manufacturer, s.purchase_price_paise,
                   s.scheme_description, p.mrp_paise, s.is_preferred_source
              FROM distributor_supply_item s
              JOIN pharmacy_product p ON p.id = s.product_id
            """
                + where
                + " ORDER BY p.name ASC LIMIT ? OFFSET ?",
            (rs, i) -> mapRaw(rs),
            pageArgs.toArray());

    List<SupplyRow> ranked = new ArrayList<>();
    for (RawSupply row : raw) {
      int rank = priceRank(pharmacyId, row.productId(), row.purchasePricePaise(), row.scheme());
      ranked.add(
          new SupplyRow(
              row.productId(),
              row.productName(),
              row.manufacturer(),
              row.purchasePricePaise(),
              row.scheme(),
              row.mrpPaise(),
              row.preferred(),
              rank));
    }
    return new ListResult(ranked, count);
  }

  @Override
  public PriceCompareResult priceCompare(
      UUID pharmacyId, boolean onlyMultiSource, String q, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE s.pharmacy_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (q != null && !q.isBlank()) {
      where.append(" AND p.name ILIKE ?");
      args.add("%" + q.trim() + "%");
    }
    if (onlyMultiSource) {
      where.append(
          """
           AND s.product_id IN (
             SELECT product_id FROM distributor_supply_item
              WHERE pharmacy_id = ?
              GROUP BY product_id HAVING COUNT(DISTINCT distributor_id) >= 2
           )
          """);
      args.add(pharmacyId);
    }

    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT s.product_id)
              FROM distributor_supply_item s
              JOIN pharmacy_product p ON p.id = s.product_id
              JOIN distributors d ON d.id = s.distributor_id AND d.deleted_at IS NULL
            """
                + where,
            Long.class,
            args.toArray());
    long count = total == null ? 0L : total;

    // Load all matching offers then paginate by product (ponytail: fine for pharmacy-scale
    // catalogs)
    List<OfferRaw> offers =
        jdbc.query(
            """
            SELECT s.product_id, p.name AS product_name, p.manufacturer,
                   s.distributor_id, d.firm_name, s.purchase_price_paise,
                   s.scheme_description, p.mrp_paise, s.is_preferred_source
              FROM distributor_supply_item s
              JOIN pharmacy_product p ON p.id = s.product_id
              JOIN distributors d ON d.id = s.distributor_id AND d.deleted_at IS NULL
            """
                + where
                + " ORDER BY p.name ASC, s.purchase_price_paise ASC",
            (rs, i) ->
                new OfferRaw(
                    (UUID) rs.getObject("product_id"),
                    rs.getString("product_name"),
                    rs.getString("manufacturer"),
                    (UUID) rs.getObject("distributor_id"),
                    rs.getString("firm_name"),
                    rs.getLong("purchase_price_paise"),
                    rs.getString("scheme_description"),
                    rs.getLong("mrp_paise"),
                    rs.getBoolean("is_preferred_source")),
            args.toArray());

    Map<UUID, PriceProduct> byProduct = new LinkedHashMap<>();
    Map<UUID, List<OfferRaw>> grouped = new LinkedHashMap<>();
    for (OfferRaw o : offers) {
      grouped.computeIfAbsent(o.productId(), k -> new ArrayList<>()).add(o);
    }
    for (Map.Entry<UUID, List<OfferRaw>> e : grouped.entrySet()) {
      List<OfferRaw> group = e.getValue();
      group.sort(
          Comparator.comparing(
              o ->
                  DistributorFormats.effectiveLandedCostPaise(o.purchasePricePaise(), o.scheme())));
      List<PriceOffer> ranked = new ArrayList<>();
      int rank = 1;
      for (OfferRaw o : group) {
        ranked.add(
            new PriceOffer(
                o.distributorId(),
                o.firmName(),
                o.purchasePricePaise(),
                o.scheme(),
                o.mrpPaise(),
                o.preferred(),
                rank++));
      }
      OfferRaw first = group.get(0);
      byProduct.put(
          e.getKey(),
          new PriceProduct(first.productId(), first.productName(), first.manufacturer(), ranked));
    }

    List<PriceProduct> all = new ArrayList<>(byProduct.values());
    int from = Math.max(0, (page - 1) * limit);
    int to = Math.min(all.size(), from + limit);
    List<PriceProduct> pageItems = from >= all.size() ? List.of() : all.subList(from, to);
    return new PriceCompareResult(pageItems, count);
  }

  @Override
  public Optional<SetPreferredResult> setPreferred(
      UUID pharmacyId, UUID distributorId, UUID productId, Instant now) {
    List<UUID> previous =
        jdbc.query(
            """
            SELECT distributor_id FROM distributor_supply_item
             WHERE pharmacy_id = ? AND product_id = ? AND is_preferred_source = TRUE
               AND distributor_id <> ?
            """,
            (rs, i) -> (UUID) rs.getObject("distributor_id"),
            pharmacyId,
            productId,
            distributorId);
    jdbc.update(
        """
        UPDATE distributor_supply_item SET is_preferred_source = FALSE, updated_at = ?
         WHERE pharmacy_id = ? AND product_id = ? AND is_preferred_source = TRUE
        """,
        Timestamp.from(now),
        pharmacyId,
        productId);
    int updated =
        jdbc.update(
            """
            UPDATE distributor_supply_item SET is_preferred_source = TRUE, updated_at = ?
             WHERE pharmacy_id = ? AND distributor_id = ? AND product_id = ?
            """,
            Timestamp.from(now),
            pharmacyId,
            distributorId,
            productId);
    if (updated == 0) {
      return Optional.empty();
    }
    return Optional.of(new SetPreferredResult(previous.isEmpty() ? null : previous.get(0)));
  }

  private int priceRank(UUID pharmacyId, UUID productId, long purchasePricePaise, String scheme) {
    BigDecimal mine = DistributorFormats.effectiveLandedCostPaise(purchasePricePaise, scheme);
    List<BigDecimal> costs =
        new ArrayList<>(
            jdbc.query(
                """
                SELECT purchase_price_paise, scheme_description
                  FROM distributor_supply_item
                 WHERE pharmacy_id = ? AND product_id = ?
                """,
                (rs, i) ->
                    DistributorFormats.effectiveLandedCostPaise(
                        rs.getLong("purchase_price_paise"), rs.getString("scheme_description")),
                pharmacyId,
                productId));
    costs.sort(Comparator.naturalOrder());
    int rank = 1;
    for (BigDecimal c : costs) {
      if (c.compareTo(mine) < 0) {
        rank++;
      }
    }
    return rank;
  }

  private static RawSupply mapRaw(ResultSet rs) throws SQLException {
    return new RawSupply(
        (UUID) rs.getObject("product_id"),
        rs.getString("product_name"),
        rs.getString("manufacturer"),
        rs.getLong("purchase_price_paise"),
        rs.getString("scheme_description"),
        rs.getLong("mrp_paise"),
        rs.getBoolean("is_preferred_source"));
  }

  private record RawSupply(
      UUID productId,
      String productName,
      String manufacturer,
      long purchasePricePaise,
      String scheme,
      long mrpPaise,
      boolean preferred) {}

  private record OfferRaw(
      UUID productId,
      String productName,
      String manufacturer,
      UUID distributorId,
      String firmName,
      long purchasePricePaise,
      String scheme,
      long mrpPaise,
      boolean preferred) {}
}
