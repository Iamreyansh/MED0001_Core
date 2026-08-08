package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.id.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** AC-4: audit_log UPDATE/DELETE blocked by DB trigger (EPIC-021 STORY-003). */
class AuditLogImmutabilityIT extends AbstractApiIT {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void updateAndDeleteAreBlocked() {
    UUID id = Ids.newId();
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address,
          actor_name, actor_type, resource_type, resource_id, "timestamp"
        ) VALUES (?, 'pharmacy', ?, 'pharmacy.suspend', NULL, 'SYSTEM', '{}'::jsonb, '0.0.0.0',
          'it', 'SYSTEM', 'pharmacy', ?, NOW())
        """,
        id,
        id,
        id);

    assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'x' WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");

    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");

    // Archival stamp is allowed.
    int updated = jdbc.update("UPDATE audit_log SET archived_at = NOW() WHERE id = ?", id);
    assertThat(updated).isEqualTo(1);
  }
}
