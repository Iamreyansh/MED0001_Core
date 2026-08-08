package com.nammamedmate.settings.application.port.out;

import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import java.util.List;
import java.util.Optional;

public interface PlatformConfigCachePort {

  Optional<List<ConfigRow>> getAll();

  void putAll(List<ConfigRow> rows);

  void invalidate();
}
