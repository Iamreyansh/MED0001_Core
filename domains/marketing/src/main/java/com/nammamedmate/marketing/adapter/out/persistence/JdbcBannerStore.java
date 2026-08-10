package com.nammamedmate.marketing.adapter.out.persistence;

import com.nammamedmate.marketing.application.port.out.BannerStore;
import com.nammamedmate.marketing.domain.Banner;
import com.nammamedmate.marketing.domain.BannerLinkType;
import com.nammamedmate.marketing.domain.BannerPlacement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcBannerStore implements BannerStore {

  private static final String SELECT =
      """
      SELECT id, headline, sub_text, image_url, placement, link_type, link_value, theme_color,
             is_live, valid_from, valid_until, priority, impressions, clicks,
             created_by, created_at, updated_at
      FROM banners
      """;

  private final JdbcTemplate jdbc;

  public JdbcBannerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Banner insert(Banner banner) {
    jdbc.update(
        """
        INSERT INTO banners (
          id, headline, sub_text, image_url, placement, link_type, link_value, theme_color,
          is_live, valid_from, valid_until, priority, impressions, clicks,
          created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        banner.id(),
        banner.headline(),
        banner.subText(),
        banner.imageUrl(),
        banner.placement().name(),
        banner.linkType().name(),
        banner.linkValue(),
        banner.themeColor(),
        banner.live(),
        Timestamp.from(banner.validFrom()),
        Timestamp.from(banner.validUntil()),
        banner.priority(),
        banner.impressions(),
        banner.clicks(),
        banner.createdBy(),
        Timestamp.from(banner.createdAt()),
        Timestamp.from(banner.updatedAt()));
    return banner;
  }

  @Override
  public Optional<Banner> findById(UUID id) {
    List<Banner> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> mapBanner(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public List<Banner> list(BannerPlacement placement, Boolean live, int offset, int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (placement != null) {
      sql.append(" AND placement = ?");
      args.add(placement.name());
    }
    if (live != null) {
      sql.append(" AND is_live = ?");
      args.add(live);
    }
    sql.append(" ORDER BY placement ASC, priority ASC, created_at ASC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, i) -> mapBanner(rs), args.toArray());
  }

  @Override
  public long count(BannerPlacement placement, Boolean live) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM banners WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (placement != null) {
      sql.append(" AND placement = ?");
      args.add(placement.name());
    }
    if (live != null) {
      sql.append(" AND is_live = ?");
      args.add(live);
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public List<Banner> listActiveForPlacement(BannerPlacement placement, Instant now) {
    return jdbc.query(
        SELECT
            + """
             WHERE placement = ?
               AND is_live = TRUE
               AND valid_from <= ?
               AND valid_until >= ?
             ORDER BY priority ASC, created_at ASC
            """,
        (rs, i) -> mapBanner(rs),
        placement.name(),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Override
  public void update(Banner banner) {
    jdbc.update(
        """
        UPDATE banners SET
          headline = ?, sub_text = ?, image_url = ?, placement = ?, link_type = ?, link_value = ?,
          theme_color = ?, is_live = ?, valid_from = ?, valid_until = ?, priority = ?,
          impressions = ?, clicks = ?, updated_at = ?
        WHERE id = ?
        """,
        banner.headline(),
        banner.subText(),
        banner.imageUrl(),
        banner.placement().name(),
        banner.linkType().name(),
        banner.linkValue(),
        banner.themeColor(),
        banner.live(),
        Timestamp.from(banner.validFrom()),
        Timestamp.from(banner.validUntil()),
        banner.priority(),
        banner.impressions(),
        banner.clicks(),
        Timestamp.from(banner.updatedAt()),
        banner.id());
  }

  @Override
  public void hardDelete(UUID id) {
    jdbc.update("DELETE FROM banners WHERE id = ?", id);
  }

  @Override
  @Transactional
  public int reorder(List<ReorderItem> items, Instant updatedAt) {
    int n = 0;
    Timestamp ts = Timestamp.from(updatedAt);
    for (ReorderItem item : items) {
      n +=
          jdbc.update(
              "UPDATE banners SET priority = ?, updated_at = ? WHERE id = ?",
              item.priority(),
              ts,
              item.id());
    }
    return n;
  }

  @Override
  public List<Banner> findByIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    return jdbc.query(
        SELECT + " WHERE id IN (" + placeholders + ")", (rs, i) -> mapBanner(rs), ids.toArray());
  }

  @Override
  public int deactivateExpired(Instant now) {
    Integer n =
        jdbc.update(
            """
            UPDATE banners SET is_live = FALSE, updated_at = ?
            WHERE is_live = TRUE AND valid_until < ?
            """,
            Timestamp.from(now),
            Timestamp.from(now));
    return n;
  }

  @Override
  public boolean incrementImpressions(UUID id) {
    int n = jdbc.update("UPDATE banners SET impressions = impressions + 1 WHERE id = ?", id);
    return n > 0;
  }

  @Override
  public boolean incrementClicks(UUID id) {
    int n = jdbc.update("UPDATE banners SET clicks = clicks + 1 WHERE id = ?", id);
    return n > 0;
  }

  private static Banner mapBanner(ResultSet rs) throws SQLException {
    UUID createdBy = (UUID) rs.getObject("created_by");
    return new Banner(
        (UUID) rs.getObject("id"),
        rs.getString("headline"),
        rs.getString("sub_text"),
        rs.getString("image_url"),
        BannerPlacement.valueOf(rs.getString("placement")),
        BannerLinkType.valueOf(rs.getString("link_type")),
        rs.getString("link_value"),
        rs.getString("theme_color"),
        rs.getBoolean("is_live"),
        rs.getTimestamp("valid_from").toInstant(),
        rs.getTimestamp("valid_until").toInstant(),
        rs.getInt("priority"),
        rs.getLong("impressions"),
        rs.getLong("clicks"),
        createdBy,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
