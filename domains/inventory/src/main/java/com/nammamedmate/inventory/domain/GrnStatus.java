package com.nammamedmate.inventory.domain;

public enum GrnStatus {
  DRAFT,
  SAVED,
  STOCKED;

  public boolean editable() {
    return this == DRAFT || this == SAVED;
  }
}
