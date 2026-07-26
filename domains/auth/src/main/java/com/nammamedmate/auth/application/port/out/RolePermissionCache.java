package com.nammamedmate.auth.application.port.out;

import java.util.List;
import java.util.UUID;

public interface RolePermissionCache {

  void put(UUID roleId, List<String> permissions);

  OptionalPermissions get(UUID roleId);

  void invalidate(UUID roleId);

  void putAll(java.util.Map<UUID, List<String>> entries);

  record OptionalPermissions(boolean present, List<String> permissions) {
    public OptionalPermissions {
      permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static OptionalPermissions miss() {
      return new OptionalPermissions(false, List.of());
    }

    public static OptionalPermissions hit(List<String> permissions) {
      return new OptionalPermissions(true, permissions);
    }
  }
}
