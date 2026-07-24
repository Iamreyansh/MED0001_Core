package com.nammamedmate.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class PharmacyContext {

  private PharmacyContext() {}

  public static Optional<UUID> currentPharmacyId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof MedmatePrincipal principal)) {
      return Optional.empty();
    }
    return Optional.ofNullable(principal.pharmacyId());
  }

  public static Optional<MedmatePrincipal> currentPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof MedmatePrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal);
  }
}
