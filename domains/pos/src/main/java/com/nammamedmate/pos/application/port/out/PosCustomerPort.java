package com.nammamedmate.pos.application.port.out;

import java.util.UUID;

/** Customer resolve/create for POS attach — bridged in apps/api. */
public interface PosCustomerPort {

  record CustomerRef(UUID customerId, String name, String phone, boolean isNew) {}

  CustomerRef findOrCreate(String phone, String name);
}
