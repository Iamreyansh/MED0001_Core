package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.domain.SeedCatalogEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeedCatalogAdapter implements SeedCatalogPort {

  private final JdbcTemplate jdbc;

  public JdbcSeedCatalogAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SeedCatalogEntry> findByKey(String seedRuleKey) {
    if (seedRuleKey == null || seedRuleKey.isBlank()) {
      return Optional.empty();
    }
    List<SeedCatalogEntry> rows =
        jdbc.query(
            "SELECT * FROM automation_seed_rule_catalog WHERE seed_rule_key = ?",
            (rs, i) -> mapRow(rs),
            seedRuleKey);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SeedCatalogEntry> findByRuleId(UUID ruleId) {
    if (ruleId == null) {
      return Optional.empty();
    }
    List<SeedCatalogEntry> rows =
        jdbc.query(
            "SELECT * FROM automation_seed_rule_catalog WHERE rule_id = ?",
            (rs, i) -> mapRow(rs),
            ruleId);
    return rows.stream().findFirst();
  }

  @Override
  public List<SeedCatalogEntry> listAll() {
    return jdbc.query(
        "SELECT * FROM automation_seed_rule_catalog ORDER BY display_order", (rs, i) -> mapRow(rs));
  }

  @Override
  public void insert(SeedCatalogEntry entry) {
    jdbc.update(
        """
        INSERT INTO automation_seed_rule_catalog (
          seed_rule_key, rule_id, workflow_id, display_order,
          expected_impact, edge_cases, initialized_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        entry.seedRuleKey(),
        entry.ruleId(),
        entry.workflowId(),
        entry.displayOrder(),
        entry.expectedImpact(),
        entry.edgeCases(),
        Timestamp.from(entry.initializedAt() == null ? Instant.EPOCH : entry.initializedAt()));
  }

  private static SeedCatalogEntry mapRow(ResultSet rs) throws SQLException {
    Timestamp ts = rs.getTimestamp("initialized_at");
    Object ruleId = rs.getObject("rule_id");
    Object workflowId = rs.getObject("workflow_id");
    return new SeedCatalogEntry(
        rs.getString("seed_rule_key"),
        ruleId == null ? null : (UUID) ruleId,
        workflowId == null ? null : (UUID) workflowId,
        rs.getInt("display_order"),
        rs.getString("expected_impact"),
        rs.getString("edge_cases"),
        ts == null ? Instant.EPOCH : ts.toInstant());
  }
}
