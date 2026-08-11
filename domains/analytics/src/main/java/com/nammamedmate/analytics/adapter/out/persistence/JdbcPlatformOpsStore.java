package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlatformOpsStore implements PlatformOpsStore {

  private static final int DEFAULT_SLA_MINUTES = 45;

  private final JdbcTemplate jdbc;

  public JdbcPlatformOpsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean zoneExists(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM zones
            WHERE id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            zoneId);
    return n != null && n > 0;
  }

  @Override
  public long liveOrdersNow(UUID zoneIdOrNull) {
    String sql =
        """
        SELECT COUNT(1)
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        WHERE o.deleted_at IS NULL
          AND o.status IN (
            'PENDING_ACCEPTANCE', 'ACCEPTED', 'PACKING',
            'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY')
        """
            + (zoneIdOrNull == null ? "" : " AND p.zone_id = ?");
    Long n =
        zoneIdOrNull == null
            ? jdbc.queryForObject(sql, Long.class)
            : jdbc.queryForObject(sql, Long.class, zoneIdOrNull);
    return n == null ? 0L : n;
  }

  @Override
  public OpsTotals liveOps(Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull) {
    String zoneFilter = zoneIdOrNull == null ? "" : " AND p.zone_id = ?";
    String sql =
        """
        SELECT
          COUNT(1) FILTER (
            WHERE o.status <> 'PAYMENT_PENDING'
          ) AS orders_placed,
          COUNT(1) FILTER (WHERE o.accepted_at IS NOT NULL) AS orders_accepted,
          COUNT(1) FILTER (WHERE o.ready_for_pickup_at IS NOT NULL) AS orders_packed,
          COUNT(1) FILTER (
            WHERE o.status IN ('OUT_FOR_DELIVERY', 'DELIVERED')
               OR EXISTS (
                 SELECT 1 FROM order_status_event e
                 WHERE e.order_id = o.id AND e.to_status = 'OUT_FOR_DELIVERY')
          ) AS orders_ofd,
          COUNT(1) FILTER (WHERE o.delivered_at IS NOT NULL) AS orders_delivered,
          COUNT(1) FILTER (WHERE o.status = 'CANCELLED') AS orders_cancelled,
          COUNT(1) FILTER (
            WHERE o.status = 'CANCELLED' AND o.accepted_at IS NULL
          ) AS pre_accept_cancelled,
          COUNT(1) FILTER (
            WHERE NOT (o.status = 'CANCELLED' AND o.accepted_at IS NULL)
              AND o.status <> 'PAYMENT_PENDING'
          ) AS fill_denom,
          COUNT(1) FILTER (
            WHERE o.delivered_at IS NOT NULL
              AND COALESCE(o.sla_breached, FALSE)
          ) AS sla_breached,
          COALESCE(SUM(
            CASE WHEN o.ready_for_pickup_at IS NOT NULL
              THEN EXTRACT(EPOCH FROM (o.ready_for_pickup_at - o.created_at))
              ELSE 0 END
          ), 0)::bigint AS total_prep_seconds,
          COALESCE(SUM(
            CASE WHEN o.delivered_at IS NOT NULL
              THEN EXTRACT(EPOCH FROM (o.delivered_at - o.created_at))
              ELSE 0 END
          ), 0)::bigint AS total_delivery_seconds,
          COALESCE(MAX(z.sla_minutes), ?) AS sla_threshold_minutes
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id AND z.deleted_at IS NULL
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        """
            + zoneFilter;
    List<Object> args = new ArrayList<>();
    args.add(DEFAULT_SLA_MINUTES);
    args.add(Timestamp.from(fromInclusive));
    args.add(Timestamp.from(toExclusive));
    if (zoneIdOrNull != null) {
      args.add(zoneIdOrNull);
    }
    List<OpsTotals> rows =
        jdbc.query(
            sql,
            (rs, i) ->
                new OpsTotals(
                    rs.getLong("orders_placed"),
                    rs.getLong("orders_accepted"),
                    rs.getLong("orders_packed"),
                    rs.getLong("orders_ofd"),
                    rs.getLong("orders_delivered"),
                    rs.getLong("orders_cancelled"),
                    rs.getLong("pre_accept_cancelled"),
                    rs.getLong("fill_denom"),
                    rs.getLong("sla_breached"),
                    rs.getLong("total_prep_seconds"),
                    rs.getLong("total_delivery_seconds"),
                    rs.getInt("sla_threshold_minutes")),
            args.toArray());
    return emptyOps(rows);
  }

  @Override
  public OpsTotals aggregatedOps(
      LocalDate fromInclusive, LocalDate toInclusive, UUID zoneIdOrNull) {
    String zoneClause = zoneIdOrNull == null ? " AND zone_id IS NULL" : " AND zone_id = ?";
    String sql =
        """
        SELECT
          COALESCE(SUM(orders_placed), 0) AS orders_placed,
          COALESCE(SUM(orders_accepted), 0) AS orders_accepted,
          COALESCE(SUM(orders_packed), 0) AS orders_packed,
          COALESCE(SUM(orders_out_for_delivery), 0) AS orders_ofd,
          COALESCE(SUM(orders_delivered), 0) AS orders_delivered,
          COALESCE(SUM(orders_cancelled), 0) AS orders_cancelled,
          COALESCE(SUM(sla_breached_count), 0) AS sla_breached,
          COALESCE(SUM(total_prep_seconds), 0) AS total_prep_seconds,
          COALESCE(SUM(total_delivery_seconds), 0) AS total_delivery_seconds,
          COALESCE(MAX(sla_threshold_minutes), ?) AS sla_threshold_minutes
        FROM analytics_ops_snapshots
        WHERE snapshot_date >= ? AND snapshot_date <= ?
        """
            + zoneClause;
    List<Object> args = new ArrayList<>();
    args.add(DEFAULT_SLA_MINUTES);
    args.add(Date.valueOf(fromInclusive));
    args.add(Date.valueOf(toInclusive));
    if (zoneIdOrNull != null) {
      args.add(zoneIdOrNull);
    }
    List<OpsTotals> rows =
        jdbc.query(
            sql,
            (rs, i) -> {
              long placed = rs.getLong("orders_placed");
              long accepted = rs.getLong("orders_accepted");
              long cancelled = rs.getLong("orders_cancelled");
              // Completed-day approximation: pre-accept ≈ placed − accepted (pending ≈ 0).
              long preAccept = Math.max(0L, placed - accepted);
              long fillDenom = Math.max(0L, placed - preAccept);
              return new OpsTotals(
                  placed,
                  accepted,
                  rs.getLong("orders_packed"),
                  rs.getLong("orders_ofd"),
                  rs.getLong("orders_delivered"),
                  cancelled,
                  preAccept,
                  fillDenom,
                  rs.getLong("sla_breached"),
                  rs.getLong("total_prep_seconds"),
                  rs.getLong("total_delivery_seconds"),
                  rs.getInt("sla_threshold_minutes"));
            },
            args.toArray());
    return emptyOps(rows);
  }

  @Override
  public DeliverySegment liveDeliveryPlatform(Instant fromInclusive, Instant toExclusive) {
    List<DeliverySegment> rows =
        jdbc.query(
            """
            SELECT
              COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY prep_m), 0) AS prep_p50,
              COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY prep_m), 0) AS prep_p90,
              COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY pickup_m), 0) AS pickup_p50,
              COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY pickup_m), 0) AS pickup_p90,
              COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY del_m), 0) AS del_p50,
              COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY del_m), 0) AS del_p90,
              COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY total_m), 0) AS total_p50,
              COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY total_m), 0) AS total_p90
            FROM (
              SELECT
                EXTRACT(EPOCH FROM (o.ready_for_pickup_at - o.created_at)) / 60.0 AS prep_m,
                EXTRACT(EPOCH FROM (
                  a.pickup_confirmed_at - COALESCE(o.rider_assigned_at, a.accepted_at, a.created_at)
                )) / 60.0 AS pickup_m,
                EXTRACT(EPOCH FROM (o.delivered_at - a.pickup_confirmed_at)) / 60.0 AS del_m,
                EXTRACT(EPOCH FROM (o.delivered_at - o.created_at)) / 60.0 AS total_m
              FROM orders o
              LEFT JOIN LATERAL (
                SELECT aa.pickup_confirmed_at, aa.accepted_at, aa.created_at
                FROM order_assignments aa
                WHERE aa.order_id = o.id
                  AND aa.pickup_confirmed_at IS NOT NULL
                ORDER BY aa.created_at DESC
                LIMIT 1
              ) a ON TRUE
              WHERE o.deleted_at IS NULL
                AND o.delivered_at IS NOT NULL
                AND o.ready_for_pickup_at IS NOT NULL
                AND o.created_at >= ? AND o.created_at < ?
            ) x
            """,
            (rs, i) ->
                new DeliverySegment(
                    pair(rs.getDouble("prep_p50"), rs.getDouble("prep_p90")),
                    pair(rs.getDouble("pickup_p50"), rs.getDouble("pickup_p90")),
                    pair(rs.getDouble("del_p50"), rs.getDouble("del_p90")),
                    pair(rs.getDouble("total_p50"), rs.getDouble("total_p90"))),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    if (rows.isEmpty()) {
      return emptySegment();
    }
    return rows.getFirst();
  }

  @Override
  public List<ZoneDeliveryRow> liveDeliveryByZone(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT
          p.zone_id,
          COALESCE(z.name, 'Unknown') AS zone_name,
          COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY prep_m), 0) AS prep_p50,
          COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY prep_m), 0) AS prep_p90,
          COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY pickup_m), 0) AS pickup_p50,
          COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY pickup_m), 0) AS pickup_p90,
          COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY del_m), 0) AS del_p50,
          COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY del_m), 0) AS del_p90,
          COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY total_m), 0) AS total_p50,
          COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY total_m), 0) AS total_p90,
          COUNT(1) FILTER (WHERE NOT COALESCE(sla_breached, FALSE)) AS on_time,
          COUNT(1) AS delivered
        FROM (
          SELECT
            o.id,
            o.pharmacy_id,
            o.sla_breached,
            EXTRACT(EPOCH FROM (o.ready_for_pickup_at - o.created_at)) / 60.0 AS prep_m,
            EXTRACT(EPOCH FROM (
              a.pickup_confirmed_at - COALESCE(o.rider_assigned_at, a.accepted_at, a.created_at)
            )) / 60.0 AS pickup_m,
            EXTRACT(EPOCH FROM (o.delivered_at - a.pickup_confirmed_at)) / 60.0 AS del_m,
            EXTRACT(EPOCH FROM (o.delivered_at - o.created_at)) / 60.0 AS total_m
          FROM orders o
          LEFT JOIN LATERAL (
            SELECT aa.pickup_confirmed_at, aa.accepted_at, aa.created_at
            FROM order_assignments aa
            WHERE aa.order_id = o.id
              AND aa.pickup_confirmed_at IS NOT NULL
            ORDER BY aa.created_at DESC
            LIMIT 1
          ) a ON TRUE
          WHERE o.deleted_at IS NULL
            AND o.delivered_at IS NOT NULL
            AND o.ready_for_pickup_at IS NOT NULL
            AND o.created_at >= ? AND o.created_at < ?
        ) x
        JOIN pharmacies p ON p.id = x.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id AND z.deleted_at IS NULL
        GROUP BY p.zone_id, z.name
        ORDER BY zone_name
        """,
        (rs, i) -> {
          long delivered = rs.getLong("delivered");
          long onTime = rs.getLong("on_time");
          return new ZoneDeliveryRow(
              (UUID) rs.getObject("zone_id"),
              rs.getString("zone_name"),
              new DeliverySegment(
                  pair(rs.getDouble("prep_p50"), rs.getDouble("prep_p90")),
                  pair(rs.getDouble("pickup_p50"), rs.getDouble("pickup_p90")),
                  pair(rs.getDouble("del_p50"), rs.getDouble("del_p90")),
                  pair(rs.getDouble("total_p50"), rs.getDouble("total_p90"))),
              AnalyticsMath.ratioPct(onTime, delivered));
        },
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public CancelSummary liveCancellations(
      Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull) {
    return loadCancellationsFromOrders(fromInclusive, toExclusive, zoneIdOrNull);
  }

  @Override
  public CancelSummary aggregatedCancellations(
      Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull) {
    String zoneFilter = zoneIdOrNull == null ? "" : " AND c.zone_id = ?";
    String baseFrom =
        """
        FROM analytics_cancellation_reasons c
        JOIN pharmacies p ON p.id = c.pharmacy_id
        LEFT JOIN zones z ON z.id = c.zone_id
        WHERE c.cancelled_at >= ? AND c.cancelled_at < ?
        """
            + zoneFilter;

    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(fromInclusive));
    args.add(Timestamp.from(toExclusive));
    if (zoneIdOrNull != null) {
      args.add(zoneIdOrNull);
    }

    long total = queryLong("SELECT COUNT(1) " + baseFrom, args.toArray());
    long pre =
        queryLong(
            "SELECT COUNT(1) " + baseFrom + " AND c.cancel_stage = 'PRE_ACCEPT'", args.toArray());
    long post = Math.max(0L, total - pre);

    List<CancelReasonRow> reasons =
        jdbc.query(
            """
            SELECT LOWER(c.cancel_reason) AS reason,
                   LOWER(c.cancel_actor) AS actor,
                   COUNT(1) AS cnt
            """
                + baseFrom
                + """
            GROUP BY LOWER(c.cancel_reason), LOWER(c.cancel_actor)
            ORDER BY cnt DESC
            """,
            (rs, i) ->
                new CancelReasonRow(
                    rs.getString("reason"),
                    rs.getString("actor").toUpperCase(Locale.ROOT),
                    rs.getLong("cnt")),
            args.toArray());

    List<CancelPharmacyRow> pharmacies =
        jdbc.query(
            """
            SELECT c.pharmacy_id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS name,
                   COUNT(1) AS cancellations,
                   (
                     SELECT COUNT(1) FROM orders o2
                     WHERE o2.pharmacy_id = c.pharmacy_id
                       AND o2.deleted_at IS NULL
                       AND o2.created_at >= ? AND o2.created_at < ?
                   ) AS pharmacy_orders
            """
                + baseFrom
                + """
            GROUP BY c.pharmacy_id, p.business_name, p.name
            ORDER BY cancellations DESC
            LIMIT 10
            """,
            (rs, i) ->
                new CancelPharmacyRow(
                    (UUID) rs.getObject("pharmacy_id"),
                    rs.getString("name"),
                    rs.getLong("cancellations"),
                    rs.getLong("pharmacy_orders")),
            prepend(args, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));

    List<CancelZoneRow> zones =
        jdbc.query(
            """
            SELECT c.zone_id,
                   COALESCE(z.name, 'Unknown') AS zone_name,
                   COUNT(1) AS cancellations,
                   (
                     SELECT COUNT(1) FROM orders o2
                     JOIN pharmacies p2 ON p2.id = o2.pharmacy_id
                     WHERE p2.zone_id IS NOT DISTINCT FROM c.zone_id
                       AND o2.deleted_at IS NULL
                       AND o2.created_at >= ? AND o2.created_at < ?
                   ) AS zone_orders
            """
                + baseFrom
                + """
            GROUP BY c.zone_id, z.name
            ORDER BY cancellations DESC
            """,
            (rs, i) ->
                new CancelZoneRow(
                    (UUID) rs.getObject("zone_id"),
                    rs.getString("zone_name"),
                    rs.getLong("cancellations"),
                    rs.getLong("zone_orders")),
            prepend(args, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));

    return new CancelSummary(total, pre, post, reasons, pharmacies, zones);
  }

  private CancelSummary loadCancellationsFromOrders(
      Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull) {
    String zoneFilter = zoneIdOrNull == null ? "" : " AND p.zone_id = ?";
    String base =
        """
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        LEFT JOIN order_cancellation oc ON oc.order_id = o.id
        WHERE o.deleted_at IS NULL
          AND o.status = 'CANCELLED'
          AND COALESCE(oc.cancelled_at, o.updated_at) >= ?
          AND COALESCE(oc.cancelled_at, o.updated_at) < ?
        """
            + zoneFilter;

    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(fromInclusive));
    args.add(Timestamp.from(toExclusive));
    if (zoneIdOrNull != null) {
      args.add(zoneIdOrNull);
    }

    long total = queryLong("SELECT COUNT(1) " + base, args.toArray());
    long pre = queryLong("SELECT COUNT(1) " + base + " AND o.accepted_at IS NULL", args.toArray());
    long post = Math.max(0L, total - pre);

    List<CancelReasonRow> rawReasons =
        jdbc.query(
            """
            SELECT
              LOWER(COALESCE(NULLIF(TRIM(o.cancel_reason), ''), NULLIF(TRIM(oc.reason), ''), 'other'))
                AS reason_raw,
              UPPER(COALESCE(oc.cancelled_by_type, 'SYSTEM')) AS actor_raw,
              COUNT(1) AS cnt
            """
                + base
                + """
            GROUP BY 1, 2
            ORDER BY cnt DESC
            """,
            (rs, i) ->
                new CancelReasonRow(
                    rs.getString("reason_raw"), rs.getString("actor_raw"), rs.getLong("cnt")),
            args.toArray());
    List<CancelReasonRow> reasons = mergeNormalizedReasons(rawReasons);

    List<CancelPharmacyRow> pharmacies =
        jdbc.query(
            """
            SELECT o.pharmacy_id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS name,
                   COUNT(1) AS cancellations,
                   (
                     SELECT COUNT(1) FROM orders o2
                     WHERE o2.pharmacy_id = o.pharmacy_id
                       AND o2.deleted_at IS NULL
                       AND o2.created_at >= ? AND o2.created_at < ?
                   ) AS pharmacy_orders
            """
                + base
                + """
            GROUP BY o.pharmacy_id, p.business_name, p.name
            ORDER BY cancellations DESC
            LIMIT 10
            """,
            (rs, i) ->
                new CancelPharmacyRow(
                    (UUID) rs.getObject("pharmacy_id"),
                    rs.getString("name"),
                    rs.getLong("cancellations"),
                    rs.getLong("pharmacy_orders")),
            prepend(args, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));

    List<CancelZoneRow> zones =
        jdbc.query(
            """
            SELECT p.zone_id,
                   COALESCE(z.name, 'Unknown') AS zone_name,
                   COUNT(1) AS cancellations,
                   (
                     SELECT COUNT(1) FROM orders o2
                     JOIN pharmacies p2 ON p2.id = o2.pharmacy_id
                     WHERE p2.zone_id IS NOT DISTINCT FROM p.zone_id
                       AND o2.deleted_at IS NULL
                       AND o2.created_at >= ? AND o2.created_at < ?
                   ) AS zone_orders
            """
                + base
                + """
            GROUP BY p.zone_id, z.name
            ORDER BY cancellations DESC
            """,
            (rs, i) ->
                new CancelZoneRow(
                    (UUID) rs.getObject("zone_id"),
                    rs.getString("zone_name"),
                    rs.getLong("cancellations"),
                    rs.getLong("zone_orders")),
            prepend(args, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));

    return new CancelSummary(total, pre, post, reasons, pharmacies, zones);
  }

  static List<CancelReasonRow> mergeNormalizedReasons(List<CancelReasonRow> raw) {
    java.util.LinkedHashMap<String, CancelReasonRow> merged = new java.util.LinkedHashMap<>();
    for (CancelReasonRow r : raw) {
      String[] norm = normalizeReason(r.reason(), r.actor());
      String key = norm[0] + "|" + norm[1];
      CancelReasonRow prev = merged.get(key);
      long count = r.count() + (prev == null ? 0L : prev.count());
      merged.put(key, new CancelReasonRow(norm[0], norm[1], count));
    }
    List<CancelReasonRow> out = new ArrayList<>(merged.values());
    out.sort((a, b) -> Long.compare(b.count(), a.count()));
    return out;
  }

  /** Returns [reason, actor] with story category codes. */
  static String[] normalizeReason(String reasonRaw, String actorRaw) {
    String reason = reasonRaw == null ? "other" : reasonRaw.toLowerCase(Locale.ROOT).trim();
    String actor = actorRaw == null ? "SYSTEM" : actorRaw.toUpperCase(Locale.ROOT).trim();
    return switch (reason) {
      case "changed_mind", "duplicate_order", "wrong_address" -> new String[] {reason, "CUSTOMER"};
      case "wrong_items" -> new String[] {"wrong_address", "CUSTOMER"};
      case "pharmacy_delay", "other" -> {
        if ("PHARMACY".equals(actor)) {
          yield new String[] {"out_of_stock", "PHARMACY"};
        }
        if ("CUSTOMER".equals(actor) || "ADMIN".equals(actor)) {
          yield new String[] {"changed_mind", "CUSTOMER"};
        }
        yield new String[] {"timeout", "SYSTEM"};
      }
      case "out_of_stock", "closing_soon", "incomplete_prescription" ->
          new String[] {reason, "PHARMACY"};
      case "cannot_fulfil" -> new String[] {"incomplete_prescription", "PHARMACY"};
      case "no_rider_available", "payment_failed", "timeout" -> new String[] {reason, "SYSTEM"};
      default -> {
        if ("PHARMACY".equals(actor)) {
          yield new String[] {"out_of_stock", "PHARMACY"};
        }
        if ("CUSTOMER".equals(actor) || "ADMIN".equals(actor)) {
          yield new String[] {"changed_mind", "CUSTOMER"};
        }
        yield new String[] {"timeout", "SYSTEM"};
      }
    };
  }

  @Override
  @Transactional
  public void refreshOpsSnapshots(LocalDate fromInclusive, LocalDate toInclusive) {
    for (LocalDate day = fromInclusive; !day.isAfter(toInclusive); day = day.plusDays(1)) {
      Instant from = day.atStartOfDay(PeriodResolver.IST).toInstant();
      Instant to = day.plusDays(1).atStartOfDay(PeriodResolver.IST).toInstant();
      upsertPlatformOps(day, from, to);
      upsertZoneOps(day, from, to);
      syncCancellationReasons(from, to);
    }
  }

  private void upsertPlatformOps(LocalDate day, Instant from, Instant to) {
    OpsTotals t = liveOps(from, to, null);
    jdbc.update(
        """
        INSERT INTO analytics_ops_snapshots (
          id, snapshot_date, zone_id, sla_threshold_minutes,
          orders_placed, orders_accepted, orders_packed, orders_out_for_delivery,
          orders_delivered, orders_cancelled, sla_breached_count,
          total_prep_seconds, total_delivery_seconds, created_at
        ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
        ON CONFLICT (snapshot_date) WHERE zone_id IS NULL DO UPDATE SET
          sla_threshold_minutes = EXCLUDED.sla_threshold_minutes,
          orders_placed = EXCLUDED.orders_placed,
          orders_accepted = EXCLUDED.orders_accepted,
          orders_packed = EXCLUDED.orders_packed,
          orders_out_for_delivery = EXCLUDED.orders_out_for_delivery,
          orders_delivered = EXCLUDED.orders_delivered,
          orders_cancelled = EXCLUDED.orders_cancelled,
          sla_breached_count = EXCLUDED.sla_breached_count,
          total_prep_seconds = EXCLUDED.total_prep_seconds,
          total_delivery_seconds = EXCLUDED.total_delivery_seconds
        """,
        UUID.randomUUID(),
        Date.valueOf(day),
        t.slaThresholdMinutes(),
        (int) t.ordersPlaced(),
        (int) t.ordersAccepted(),
        (int) t.ordersPacked(),
        (int) t.ordersOutForDelivery(),
        (int) t.ordersDelivered(),
        (int) t.ordersCancelled(),
        (int) t.slaBreached(),
        t.totalPrepSeconds(),
        t.totalDeliverySeconds());
  }

  private void upsertZoneOps(LocalDate day, Instant from, Instant to) {
    jdbc.update(
        "DELETE FROM analytics_ops_snapshots WHERE snapshot_date = ? AND zone_id IS NOT NULL",
        Date.valueOf(day));
    List<UUID> zoneIds =
        jdbc.query(
            """
            SELECT DISTINCT p.zone_id
            FROM orders o
            JOIN pharmacies p ON p.id = o.pharmacy_id
            WHERE o.deleted_at IS NULL
              AND o.created_at >= ? AND o.created_at < ?
              AND p.zone_id IS NOT NULL
            """,
            (rs, i) -> (UUID) rs.getObject(1),
            Timestamp.from(from),
            Timestamp.from(to));
    for (UUID zoneId : zoneIds) {
      OpsTotals t = liveOps(from, to, zoneId);
      jdbc.update(
          """
          INSERT INTO analytics_ops_snapshots (
            id, snapshot_date, zone_id, sla_threshold_minutes,
            orders_placed, orders_accepted, orders_packed, orders_out_for_delivery,
            orders_delivered, orders_cancelled, sla_breached_count,
            total_prep_seconds, total_delivery_seconds, created_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
          """,
          UUID.randomUUID(),
          Date.valueOf(day),
          zoneId,
          t.slaThresholdMinutes(),
          (int) t.ordersPlaced(),
          (int) t.ordersAccepted(),
          (int) t.ordersPacked(),
          (int) t.ordersOutForDelivery(),
          (int) t.ordersDelivered(),
          (int) t.ordersCancelled(),
          (int) t.slaBreached(),
          t.totalPrepSeconds(),
          t.totalDeliverySeconds());
    }
  }

  private void syncCancellationReasons(Instant from, Instant to) {
    jdbc.update(
        """
        INSERT INTO analytics_cancellation_reasons (
          id, order_id, pharmacy_id, zone_id, cancel_stage, cancel_reason, cancel_actor, cancelled_at
        )
        SELECT
          gen_random_uuid(),
          o.id,
          o.pharmacy_id,
          p.zone_id,
          CASE WHEN o.accepted_at IS NULL THEN 'PRE_ACCEPT' ELSE 'POST_ACCEPT' END,
          CASE
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) IN (
              'changed_mind','wrong_address','duplicate_order') THEN LOWER(COALESCE(o.cancel_reason, oc.reason))
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) = 'wrong_items' THEN 'wrong_address'
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) IN (
              'out_of_stock','closing_soon','incomplete_prescription') THEN LOWER(COALESCE(o.cancel_reason, oc.reason))
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) = 'cannot_fulfil' THEN 'incomplete_prescription'
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) IN (
              'no_rider_available','payment_failed','timeout') THEN LOWER(COALESCE(o.cancel_reason, oc.reason))
            WHEN UPPER(COALESCE(oc.cancelled_by_type, '')) = 'PHARMACY' THEN 'out_of_stock'
            WHEN UPPER(COALESCE(oc.cancelled_by_type, '')) = 'CUSTOMER' THEN 'changed_mind'
            ELSE 'timeout'
          END,
          CASE
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) IN (
              'changed_mind','wrong_address','duplicate_order','wrong_items')
              OR UPPER(COALESCE(oc.cancelled_by_type, '')) IN ('CUSTOMER','ADMIN')
              THEN 'CUSTOMER'
            WHEN LOWER(COALESCE(o.cancel_reason, oc.reason, '')) IN (
              'out_of_stock','closing_soon','incomplete_prescription','cannot_fulfil')
              OR UPPER(COALESCE(oc.cancelled_by_type, '')) = 'PHARMACY'
              THEN 'PHARMACY'
            ELSE 'SYSTEM'
          END,
          COALESCE(oc.cancelled_at, o.updated_at)
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN order_cancellation oc ON oc.order_id = o.id
        WHERE o.deleted_at IS NULL
          AND o.status = 'CANCELLED'
          AND COALESCE(oc.cancelled_at, o.updated_at) >= ?
          AND COALESCE(oc.cancelled_at, o.updated_at) < ?
        ON CONFLICT (order_id) DO UPDATE SET
          pharmacy_id = EXCLUDED.pharmacy_id,
          zone_id = EXCLUDED.zone_id,
          cancel_stage = EXCLUDED.cancel_stage,
          cancel_reason = EXCLUDED.cancel_reason,
          cancel_actor = EXCLUDED.cancel_actor,
          cancelled_at = EXCLUDED.cancelled_at
        """,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  private long queryLong(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0L : n;
  }

  private static Object[] prepend(List<Object> args, Object a, Object b) {
    List<Object> all = new ArrayList<>();
    all.add(a);
    all.add(b);
    all.addAll(args);
    return all.toArray();
  }

  private static PercentilePair pair(double p50, double p90) {
    return new PercentilePair(scale1(p50), scale1(p90));
  }

  private static BigDecimal scale1(double v) {
    return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
  }

  private static DeliverySegment emptySegment() {
    PercentilePair z = new PercentilePair(BigDecimal.ZERO.setScale(1), BigDecimal.ZERO.setScale(1));
    return new DeliverySegment(z, z, z, z);
  }

  private static OpsTotals emptyOps(List<OpsTotals> rows) {
    if (rows == null || rows.isEmpty()) {
      return new OpsTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, DEFAULT_SLA_MINUTES);
    }
    return rows.getFirst();
  }
}
