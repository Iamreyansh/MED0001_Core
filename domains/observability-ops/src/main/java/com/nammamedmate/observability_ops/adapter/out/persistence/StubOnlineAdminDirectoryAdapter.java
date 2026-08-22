package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.OnlineAdminDirectoryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class StubOnlineAdminDirectoryAdapter implements OnlineAdminDirectoryPort {

  private final CopyOnWriteArrayList<UUID> online = new CopyOnWriteArrayList<>();
  private final JdbcTemplate jdbc;
  private boolean overridden;

  public StubOnlineAdminDirectoryAdapter() {
    this(null);
  }

  @Autowired
  public StubOnlineAdminDirectoryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    online.add(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
  }

  @Override
  public List<UUID> onlineAdminIds(Set<String> roles) {
    if (overridden || jdbc == null) {
      return List.copyOf(online);
    }
    try {
      if (roles == null || roles.isEmpty()) {
        return jdbc.query(
            """
            SELECT id FROM admin_staff
             WHERE deleted_at IS NULL AND status = 'ACTIVE'
             ORDER BY last_active_at DESC NULLS LAST
             LIMIT 20
            """,
            (rs, i) -> (UUID) rs.getObject("id"));
      }
      StringBuilder in = new StringBuilder();
      List<Object> args = new ArrayList<>();
      for (String role : roles) {
        if (role == null || role.isBlank()) {
          continue;
        }
        if (!in.isEmpty()) {
          in.append(',');
        }
        in.append('?');
        args.add(role);
      }
      if (args.isEmpty()) {
        return onlineAdminIds(Set.of());
      }
      return jdbc.query(
          "SELECT id FROM admin_staff"
              + " WHERE deleted_at IS NULL AND status = 'ACTIVE'"
              + " AND role IN ("
              + in
              + ") ORDER BY last_active_at DESC NULLS LAST LIMIT 20",
          (rs, i) -> (UUID) rs.getObject("id"),
          args.toArray());
    } catch (RuntimeException ex) {
      return List.copyOf(online);
    }
  }

  public void setOnline(List<UUID> ids) {
    overridden = true;
    online.clear();
    online.addAll(ids);
  }
}
