package com.nammamedmate.order.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcCartStore implements CartStore {

  private static final TypeReference<List<Map<String, Object>>> ITEMS_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcCartStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<Cart> findActiveByCustomer(UUID customerId) {
    List<Cart> rows =
        jdbc.query(
            """
            SELECT * FROM carts
            WHERE customer_id = ? AND status = 'ACTIVE'
            LIMIT 1
            """,
            this::mapCart,
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<Cart> findById(UUID cartId) {
    List<Cart> rows = jdbc.query("SELECT * FROM carts WHERE id = ?", this::mapCart, cartId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Cart insert(Cart cart) {
    jdbc.update(
        """
        INSERT INTO carts (
          id, customer_id, pharmacy_id, items, coupon_code, coupon_discount_paise,
          prescription_id, delivery_address_id, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
        """,
        cart.id(),
        cart.customerId(),
        cart.pharmacyId(),
        toJson(cart.items()),
        cart.couponCode(),
        cart.couponDiscountPaise(),
        cart.prescriptionId(),
        cart.deliveryAddressId(),
        cart.status().name(),
        Timestamp.from(cart.createdAt()),
        Timestamp.from(cart.updatedAt()));
    return cart;
  }

  @Override
  public Cart update(Cart cart) {
    int n =
        jdbc.update(
            """
            UPDATE carts SET
              pharmacy_id = ?,
              items = ?::jsonb,
              coupon_code = ?,
              coupon_discount_paise = ?,
              prescription_id = ?,
              delivery_address_id = ?,
              status = ?,
              updated_at = ?
            WHERE id = ? AND customer_id = ?
            """,
            cart.pharmacyId(),
            toJson(cart.items()),
            cart.couponCode(),
            cart.couponDiscountPaise(),
            cart.prescriptionId(),
            cart.deliveryAddressId(),
            cart.status().name(),
            Timestamp.from(cart.updatedAt()),
            cart.id(),
            cart.customerId());
    if (n == 0) {
      throw new IllegalStateException("cart not found for update: " + cart.id());
    }
    return cart;
  }

  @Override
  public int abandonStale(Instant cutoff) {
    return jdbc.update(
        """
        UPDATE carts
        SET status = 'ABANDONED', updated_at = NOW()
        WHERE status = 'ACTIVE' AND updated_at < ?
        """,
        Timestamp.from(cutoff));
  }

  private Cart mapCart(ResultSet rs, int rowNum) throws SQLException {
    return new Cart(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("pharmacy_id"),
        fromJson(rs.getString("items")),
        rs.getString("coupon_code"),
        rs.getLong("coupon_discount_paise"),
        (UUID) rs.getObject("prescription_id"),
        (UUID) rs.getObject("delivery_address_id"),
        CartStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private String toJson(List<CartItem> items) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (CartItem item : items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", item.itemId().toString());
        m.put("product_id", item.productId().toString());
        m.put("quantity", item.quantity());
        m.put("unit_price_paise", item.unitPricePaise());
        m.put("line_total_paise", item.lineTotalPaise());
        m.put("is_rx_required", item.rxRequired());
        m.put("name", item.name());
        m.put("brand", item.brand());
        m.put("pack_size", item.packSize());
        m.put("image_url", item.imageUrl());
        rows.add(m);
      }
      return objectMapper.writeValueAsString(rows);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cart items serialize failed", e);
    }
  }

  private List<CartItem> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, ITEMS_TYPE);
      List<CartItem> items = new ArrayList<>();
      for (Map<String, Object> m : rows) {
        items.add(
            new CartItem(
                UUID.fromString(String.valueOf(m.get("item_id"))),
                UUID.fromString(String.valueOf(m.get("product_id"))),
                ((Number) m.get("quantity")).intValue(),
                ((Number) m.get("unit_price_paise")).longValue(),
                Boolean.TRUE.equals(m.get("is_rx_required"))
                    || "true".equalsIgnoreCase(String.valueOf(m.get("is_rx_required"))),
                m.get("name") == null ? null : String.valueOf(m.get("name")),
                m.get("brand") == null ? null : String.valueOf(m.get("brand")),
                m.get("pack_size") == null ? null : String.valueOf(m.get("pack_size")),
                m.get("image_url") == null || "null".equals(String.valueOf(m.get("image_url")))
                    ? null
                    : String.valueOf(m.get("image_url"))));
      }
      return items;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cart items deserialize failed", e);
    }
  }
}
