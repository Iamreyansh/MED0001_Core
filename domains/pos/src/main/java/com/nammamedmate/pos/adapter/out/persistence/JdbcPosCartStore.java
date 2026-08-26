package com.nammamedmate.pos.adapter.out.persistence;

import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPosCartStore implements PosCartStore {

  private static final RowMapper<PosCart> CART_MAPPER = JdbcPosCartStore::mapCart;
  private static final RowMapper<PosCartItem> ITEM_MAPPER = JdbcPosCartStore::mapItem;

  private final JdbcTemplate jdbc;

  public JdbcPosCartStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PosCart insert(PosCart cart) {
    jdbc.update(
        """
        INSERT INTO pos_cart (
          id, pharmacy_id, staff_id, customer_id, customer_name, customer_phone,
          prescribing_doctor, discount_type, discount_value, discount_amount_paise,
          subtotal_paise, gst_total_paise, grand_total_paise, status, expires_at,
          invoice_id, applied_offer_id, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        cart.id(),
        cart.pharmacyId(),
        cart.staffId(),
        cart.customerId(),
        cart.customerName(),
        cart.customerPhone(),
        cart.prescribingDoctor(),
        cart.discountType() == null ? null : cart.discountType().name(),
        cart.discountValue() == null ? BigDecimal.ZERO : cart.discountValue(),
        cart.discountAmountPaise(),
        cart.subtotalPaise(),
        cart.gstTotalPaise(),
        cart.grandTotalPaise(),
        cart.status().name(),
        Timestamp.from(cart.expiresAt()),
        cart.invoiceId(),
        cart.appliedOfferId(),
        Timestamp.from(cart.createdAt()),
        Timestamp.from(cart.updatedAt()));
    return cart;
  }

  @Override
  public Optional<PosCart> findById(UUID pharmacyId, UUID cartId) {
    List<PosCart> rows =
        jdbc.query(
            "SELECT * FROM pos_cart WHERE pharmacy_id = ? AND id = ?",
            CART_MAPPER,
            pharmacyId,
            cartId);
    return rows.stream().findFirst();
  }

  @Override
  public PosCart update(PosCart cart) {
    jdbc.update(
        """
        UPDATE pos_cart SET
          customer_id=?, customer_name=?, customer_phone=?, prescribing_doctor=?,
          discount_type=?, discount_value=?, discount_amount_paise=?,
          subtotal_paise=?, gst_total_paise=?, grand_total_paise=?,
          status=?, expires_at=?, invoice_id=?, applied_offer_id=?, updated_at=?
        WHERE id=?
        """,
        cart.customerId(),
        cart.customerName(),
        cart.customerPhone(),
        cart.prescribingDoctor(),
        cart.discountType() == null ? null : cart.discountType().name(),
        cart.discountValue() == null ? BigDecimal.ZERO : cart.discountValue(),
        cart.discountAmountPaise(),
        cart.subtotalPaise(),
        cart.gstTotalPaise(),
        cart.grandTotalPaise(),
        cart.status().name(),
        Timestamp.from(cart.expiresAt()),
        cart.invoiceId(),
        cart.appliedOfferId(),
        Timestamp.from(cart.updatedAt()),
        cart.id());
    return cart;
  }

  @Override
  public void touchExpiry(UUID cartId, Instant expiresAt, Instant updatedAt) {
    jdbc.update(
        "UPDATE pos_cart SET expires_at=?, updated_at=? WHERE id=?",
        Timestamp.from(expiresAt),
        Timestamp.from(updatedAt),
        cartId);
  }

  @Override
  public PosCartItem insertItem(PosCartItem item) {
    jdbc.update(
        """
        INSERT INTO pos_cart_item (
          id, cart_id, product_id, product_name, batch_id, batch_number, expiry_date,
          quantity, is_loose, unit_price_paise, gst_pct, line_subtotal_paise,
          gst_amount_paise, line_total_paise, is_rx_only, pack_size, hsn_code, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        item.id(),
        item.cartId(),
        item.productId(),
        item.productName(),
        item.batchId(),
        item.batchNumber(),
        Date.valueOf(item.expiryDate()),
        item.quantity(),
        item.isLoose(),
        item.unitPricePaise(),
        item.gstPct(),
        item.lineSubtotalPaise(),
        item.gstAmountPaise(),
        item.lineTotalPaise(),
        item.isRxOnly(),
        item.packSize(),
        item.hsnCode(),
        Timestamp.from(item.createdAt()));
    return item;
  }

  @Override
  public Optional<PosCartItem> findItem(UUID cartId, UUID itemId) {
    List<PosCartItem> rows =
        jdbc.query(
            "SELECT * FROM pos_cart_item WHERE cart_id = ? AND id = ?",
            ITEM_MAPPER,
            cartId,
            itemId);
    return rows.stream().findFirst();
  }

  @Override
  public List<PosCartItem> listItems(UUID cartId) {
    return jdbc.query(
        "SELECT * FROM pos_cart_item WHERE cart_id = ? ORDER BY created_at", ITEM_MAPPER, cartId);
  }

  @Override
  public PosCartItem updateItem(PosCartItem item) {
    jdbc.update(
        """
        UPDATE pos_cart_item SET
          batch_id=?, batch_number=?, expiry_date=?, quantity=?, is_loose=?,
          unit_price_paise=?, gst_pct=?, line_subtotal_paise=?, gst_amount_paise=?,
          line_total_paise=?, is_rx_only=?, pack_size=?, hsn_code=?
        WHERE id=? AND cart_id=?
        """,
        item.batchId(),
        item.batchNumber(),
        Date.valueOf(item.expiryDate()),
        item.quantity(),
        item.isLoose(),
        item.unitPricePaise(),
        item.gstPct(),
        item.lineSubtotalPaise(),
        item.gstAmountPaise(),
        item.lineTotalPaise(),
        item.isRxOnly(),
        item.packSize(),
        item.hsnCode(),
        item.id(),
        item.cartId());
    return item;
  }

  @Override
  public void deleteItem(UUID cartId, UUID itemId) {
    jdbc.update("DELETE FROM pos_cart_item WHERE cart_id = ? AND id = ?", cartId, itemId);
  }

  @Override
  public int deleteAllItems(UUID cartId) {
    return jdbc.update("DELETE FROM pos_cart_item WHERE cart_id = ?", cartId);
  }

  @Override
  public void updateTotals(
      UUID cartId,
      long subtotalPaise,
      long gstTotalPaise,
      long discountAmountPaise,
      long grandTotalPaise,
      String discountType,
      BigDecimal discountValue,
      UUID appliedOfferId,
      Instant updatedAt,
      Instant expiresAt) {
    jdbc.update(
        """
        UPDATE pos_cart SET
          subtotal_paise=?, gst_total_paise=?, discount_amount_paise=?, grand_total_paise=?,
          discount_type=?, discount_value=?, applied_offer_id=?, updated_at=?, expires_at=?
        WHERE id=?
        """,
        subtotalPaise,
        gstTotalPaise,
        discountAmountPaise,
        grandTotalPaise,
        discountType,
        discountValue == null ? BigDecimal.ZERO : discountValue,
        appliedOfferId,
        Timestamp.from(updatedAt),
        Timestamp.from(expiresAt),
        cartId);
  }

  @Override
  public void markCompleted(UUID cartId, UUID invoiceId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pos_cart SET status='COMPLETED', invoice_id=?, updated_at=? WHERE id=?
        """,
        invoiceId,
        Timestamp.from(updatedAt),
        cartId);
  }

  @Override
  public Optional<UUID> findInvoiceByCheckoutIdempotency(UUID pharmacyId, String idempotencyKey) {
    if (pharmacyId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    List<UUID> rows =
        jdbc.query(
            """
            SELECT invoice_id FROM pos_checkout_idempotency
            WHERE pharmacy_id = ? AND idempotency_key = ?
            LIMIT 1
            """,
            (rs, i) -> (UUID) rs.getObject("invoice_id"),
            pharmacyId,
            idempotencyKey.trim());
    return rows.stream().findFirst();
  }

  @Override
  public void saveCheckoutIdempotency(
      UUID pharmacyId, String idempotencyKey, UUID cartId, UUID invoiceId, Instant createdAt) {
    if (pharmacyId == null
        || idempotencyKey == null
        || idempotencyKey.isBlank()
        || cartId == null
        || invoiceId == null) {
      return;
    }
    jdbc.update(
        """
        INSERT INTO pos_checkout_idempotency (
          pharmacy_id, idempotency_key, cart_id, invoice_id, created_at
        ) VALUES (?,?,?,?,?)
        ON CONFLICT (pharmacy_id, idempotency_key) DO NOTHING
        """,
        pharmacyId,
        idempotencyKey.trim(),
        cartId,
        invoiceId,
        Timestamp.from(createdAt));
  }

  @Override
  public int abandonExpired(Instant now) {
    return jdbc.update(
        """
        UPDATE pos_cart SET status='ABANDONED', updated_at=?
        WHERE status='ACTIVE' AND expires_at < ?
        """,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Override
  public void attachCustomer(
      UUID cartId,
      UUID customerId,
      String name,
      String phone,
      Instant updatedAt,
      Instant expiresAt) {
    jdbc.update(
        """
        UPDATE pos_cart SET customer_id=?, customer_name=?, customer_phone=?,
          updated_at=?, expires_at=? WHERE id=?
        """,
        customerId,
        name,
        phone,
        Timestamp.from(updatedAt),
        Timestamp.from(expiresAt),
        cartId);
  }

  @Override
  public void setPrescribingDoctor(
      UUID cartId, String doctor, Instant updatedAt, Instant expiresAt) {
    jdbc.update(
        "UPDATE pos_cart SET prescribing_doctor=?, updated_at=?, expires_at=? WHERE id=?",
        doctor,
        Timestamp.from(updatedAt),
        Timestamp.from(expiresAt),
        cartId);
  }

  private static PosCart mapCart(ResultSet rs, int rowNum) throws SQLException {
    String dtype = rs.getString("discount_type");
    DiscountType discountType = parseDiscountType(dtype);
    return new PosCart(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("staff_id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("customer_name"),
        rs.getString("customer_phone"),
        rs.getString("prescribing_doctor"),
        discountType,
        rs.getBigDecimal("discount_value"),
        rs.getLong("discount_amount_paise"),
        rs.getLong("subtotal_paise"),
        rs.getLong("gst_total_paise"),
        rs.getLong("grand_total_paise"),
        PosCartStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("expires_at").toInstant(),
        (UUID) rs.getObject("invoice_id"),
        (UUID) rs.getObject("applied_offer_id"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  static DiscountType parseDiscountType(String dtype) {
    if (dtype == null) {
      return null;
    }
    return DiscountType.valueOf(dtype);
  }

  private static PosCartItem mapItem(ResultSet rs, int rowNum) throws SQLException {
    return new PosCartItem(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("cart_id"),
        (UUID) rs.getObject("product_id"),
        rs.getString("product_name"),
        (UUID) rs.getObject("batch_id"),
        rs.getString("batch_number"),
        rs.getDate("expiry_date").toLocalDate(),
        rs.getInt("quantity"),
        rs.getBoolean("is_loose"),
        rs.getLong("unit_price_paise"),
        rs.getInt("gst_pct"),
        rs.getLong("line_subtotal_paise"),
        rs.getLong("gst_amount_paise"),
        rs.getLong("line_total_paise"),
        rs.getBoolean("is_rx_only"),
        rs.getInt("pack_size"),
        rs.getString("hsn_code"),
        rs.getTimestamp("created_at").toInstant());
  }
}
