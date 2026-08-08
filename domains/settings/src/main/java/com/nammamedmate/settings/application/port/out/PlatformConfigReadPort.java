package com.nammamedmate.settings.application.port.out;

import java.util.Optional;

/**
 * Cross-app read API for platform config (Redis → DB cold fill). Wire from apps/api later; no
 * domain→domain deps.
 */
public interface PlatformConfigReadPort {

  Optional<Object> getTyped(String key);

  Optional<String> getRaw(String key);
}
