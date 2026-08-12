package com.nammamedmate.observability_ops.application.port.out;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface OnlineAdminDirectoryPort {

  List<UUID> onlineAdminIds(Set<String> roles);
}
