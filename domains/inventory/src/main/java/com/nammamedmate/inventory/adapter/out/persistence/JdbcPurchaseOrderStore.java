package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.domain.PoSentChannel;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseOrderStore implements PurchaseOrderStore {

  private static final String SELECT_PO =
      """
      SELECT id, pharmacy_id, distributor_id, po_number, status, created_by,
             sent_at, sent_channel, grn_id, created_at, updated_at, deleted_at
        FROM purchase_order
      """;

  private final JdbcTemplate jdbc;

  public JdbcPurchaseOrderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PurchaseOrder insert(PurchaseOrder po) {
    jdbc.update(
        """
        INSERT INTO purchase_order (
          id, pharmacy_id, distributor_id, po_number, status, created_by,
          sent_at, sent_channel, grn_id, created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        po.id(),
        po.pharmacyId(),
        po.distributorId(),
        po.poNumber(),
        po.status().name(),
        po.createdBy(),
        po.sentAt() == null ? null : Timestamp.from(po.sentAt()),
        po.sentChannel() == null ? null : po.sentChannel().name(),
        po.grnId(),
        Timestamp.from(po.createdAt()),
        Timestamp.from(po.updatedAt()),
        po.deletedAt() == null ? null : Timestamp.from(po.deletedAt()));
    return po;
  }

  @Override
  public PurchaseOrderItem insertItem(PurchaseOrderItem item) {
    jdbc.update(
        """
        INSERT INTO purchase_order_item (
          id, po_id, pharmacy_id, product_id, quantity, estimated_price_paise, created_at
        ) VALUES (?,?,?,?,?,?,?)
        """,
        item.id(),
        item.poId(),
        item.pharmacyId(),
        item.productId(),
        item.quantity(),
        item.estimatedPricePaise(),
        Timestamp.from(item.createdAt()));
    return item;
  }

  @Override
  public Optional<PurchaseOrder> findById(UUID pharmacyId, UUID poId) {
    List<PurchaseOrder> rows =
        jdbc.query(
            SELECT_PO + " WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL",
            PO_MAPPER,
            pharmacyId,
            poId);
    return rows.stream().findFirst();
  }

  @Override
  public ListResult list(ListFilter filter) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT po.id, po.po_number, d.firm_name,
                   (SELECT COUNT(*) FROM purchase_order_item i WHERE i.po_id = po.id) AS items_count,
                   COALESCE((
                     SELECT SUM(i.quantity * COALESCE(i.estimated_price_paise, 0))
                       FROM purchase_order_item i WHERE i.po_id = po.id
                   ), 0) AS estimated_total_paise,
                   po.status, po.created_at, po.sent_at
              FROM purchase_order po
              JOIN distributors d ON d.id = po.distributor_id
             WHERE po.pharmacy_id = ? AND po.deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    args.add(filter.pharmacyId());
    if (filter.status() != null) {
      sql.append(" AND po.status = ?");
      args.add(filter.status().name());
    }
    if (filter.distributorId() != null) {
      sql.append(" AND po.distributor_id = ?");
      args.add(filter.distributorId());
    }
    String countSql = "SELECT COUNT(*) FROM (" + sql + ") c";
    Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
    sql.append(" ORDER BY po.created_at DESC LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(Math.max(0, (filter.page() - 1) * filter.limit()));
    List<PoListRow> rows =
        jdbc.query(
            sql.toString(),
            (rs, i) ->
                new PoListRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("po_number"),
                    rs.getString("firm_name"),
                    rs.getInt("items_count"),
                    rs.getLong("estimated_total_paise"),
                    PurchaseOrderStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sent_at") == null
                        ? null
                        : rs.getTimestamp("sent_at").toInstant()),
            args.toArray());
    return new ListResult(rows, total == null ? 0L : total);
  }

  @Override
  public long countOpen(UUID pharmacyId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM purchase_order
             WHERE pharmacy_id = ?
               AND deleted_at IS NULL
               AND status IN ('DRAFT', 'SENT')
               AND grn_id IS NULL
            """,
            Long.class,
            pharmacyId);
    return n == null ? 0L : n;
  }

  @Override
  public int nextSequence(UUID pharmacyId, YearMonth yearMonth) {
    String prefix = String.format("PO-%04d-%02d-", yearMonth.getYear(), yearMonth.getMonthValue());
    List<String> numbers =
        jdbc.query(
            """
            SELECT po_number FROM purchase_order
             WHERE pharmacy_id = ? AND po_number LIKE ?
             ORDER BY po_number DESC
             LIMIT 1
            """,
            (rs, i) -> rs.getString("po_number"),
            pharmacyId,
            prefix + "%");
    if (numbers.isEmpty()) {
      return 1;
    }
    String last = numbers.get(0);
    String seqPart = last.substring(prefix.length());
    try {
      return Integer.parseInt(seqPart) + 1;
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  @Override
  public List<ItemWithProduct> listItems(UUID pharmacyId, UUID poId) {
    return jdbc.query(
        """
        SELECT i.id, i.po_id, i.pharmacy_id, i.product_id, i.quantity,
               i.estimated_price_paise, i.created_at,
               p.name AS product_name, p.mrp_paise, p.gst_pct
          FROM purchase_order_item i
          JOIN pharmacy_product p ON p.id = i.product_id
         WHERE i.pharmacy_id = ? AND i.po_id = ?
         ORDER BY p.name
        """,
        (rs, i) ->
            new ItemWithProduct(
                mapItem(rs),
                rs.getString("product_name"),
                rs.getLong("mrp_paise"),
                rs.getInt("gst_pct")),
        pharmacyId,
        poId);
  }

  @Override
  public int countItems(UUID pharmacyId, UUID poId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_item WHERE pharmacy_id = ? AND po_id = ?",
            Integer.class,
            pharmacyId,
            poId);
    return n == null ? 0 : n;
  }

  @Override
  public long estimatedTotalPaise(UUID pharmacyId, UUID poId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(quantity * COALESCE(estimated_price_paise, 0)), 0)
              FROM purchase_order_item
             WHERE pharmacy_id = ? AND po_id = ?
            """,
            Long.class,
            pharmacyId,
            poId);
    return n == null ? 0L : n;
  }

  @Override
  public boolean deleteItem(UUID pharmacyId, UUID poId, UUID itemId) {
    return jdbc.update(
            "DELETE FROM purchase_order_item WHERE pharmacy_id = ? AND po_id = ? AND id = ?",
            pharmacyId,
            poId,
            itemId)
        > 0;
  }

  @Override
  public Optional<PurchaseOrderItem> findItem(UUID pharmacyId, UUID poId, UUID itemId) {
    List<PurchaseOrderItem> rows =
        jdbc.query(
            """
            SELECT id, po_id, pharmacy_id, product_id, quantity, estimated_price_paise, created_at
              FROM purchase_order_item
             WHERE pharmacy_id = ? AND po_id = ? AND id = ?
            """,
            (rs, i) -> mapItem(rs),
            pharmacyId,
            poId,
            itemId);
    return rows.stream().findFirst();
  }

  @Override
  public PurchaseOrderItem updateItemQuantity(UUID itemId, int quantity) {
    jdbc.update("UPDATE purchase_order_item SET quantity = ? WHERE id = ?", quantity, itemId);
    List<PurchaseOrderItem> rows =
        jdbc.query(
            """
            SELECT id, po_id, pharmacy_id, product_id, quantity, estimated_price_paise, created_at
              FROM purchase_order_item WHERE id = ?
            """,
            (rs, i) -> mapItem(rs),
            itemId);
    return rows.get(0);
  }

  @Override
  public PurchaseOrder update(
      UUID poId,
      PurchaseOrderStatus status,
      Instant sentAt,
      PoSentChannel channel,
      UUID grnId,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE purchase_order
           SET status = ?, sent_at = ?, sent_channel = ?, grn_id = ?, updated_at = ?
         WHERE id = ?
        """,
        status.name(),
        sentAt == null ? null : Timestamp.from(sentAt),
        channel == null ? null : channel.name(),
        grnId,
        Timestamp.from(updatedAt),
        poId);
    List<PurchaseOrder> rows = jdbc.query(SELECT_PO + " WHERE id = ?", PO_MAPPER, poId);
    return rows.get(0);
  }

  @Override
  public void softCancel(UUID pharmacyId, UUID poId, Instant now) {
    jdbc.update(
        """
        UPDATE purchase_order
           SET status = 'CANCELLED', deleted_at = ?, updated_at = ?
         WHERE pharmacy_id = ? AND id = ?
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        pharmacyId,
        poId);
  }

  private static PurchaseOrderItem mapItem(ResultSet rs) throws SQLException {
    Object price = rs.getObject("estimated_price_paise");
    return new PurchaseOrderItem(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("po_id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("product_id"),
        rs.getInt("quantity"),
        price == null ? null : rs.getLong("estimated_price_paise"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static final RowMapper<PurchaseOrder> PO_MAPPER =
      (rs, i) -> {
        String channel = rs.getString("sent_channel");
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new PurchaseOrder(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("pharmacy_id"),
            (UUID) rs.getObject("distributor_id"),
            rs.getString("po_number"),
            PurchaseOrderStatus.valueOf(rs.getString("status")),
            (UUID) rs.getObject("created_by"),
            sentAt == null ? null : sentAt.toInstant(),
            channel == null ? null : PoSentChannel.valueOf(channel),
            (UUID) rs.getObject("grn_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            deletedAt == null ? null : deletedAt.toInstant());
      };
}
