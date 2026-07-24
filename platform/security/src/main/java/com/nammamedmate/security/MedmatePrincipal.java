package com.nammamedmate.security;

import java.util.Objects;
import java.util.UUID;

public record MedmatePrincipal(
    UUID subject, AuthRole role, UUID pharmacyId, TokenScope tokenScope, String jti) {

  public MedmatePrincipal {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(tokenScope, "tokenScope");
    Objects.requireNonNull(jti, "jti");
  }

  public boolean hasPharmacyContext() {
    return pharmacyId != null;
  }
}
