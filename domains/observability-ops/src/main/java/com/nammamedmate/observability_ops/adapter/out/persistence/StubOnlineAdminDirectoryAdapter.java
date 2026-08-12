package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.OnlineAdminDirectoryPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class StubOnlineAdminDirectoryAdapter implements OnlineAdminDirectoryPort {

  private final CopyOnWriteArrayList<UUID> online = new CopyOnWriteArrayList<>();

  public StubOnlineAdminDirectoryAdapter() {
    online.add(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
  }

  @Override
  public List<UUID> onlineAdminIds(Set<String> roles) {
    return List.copyOf(online);
  }

  public void setOnline(List<UUID> ids) {
    online.clear();
    online.addAll(ids);
  }
}
