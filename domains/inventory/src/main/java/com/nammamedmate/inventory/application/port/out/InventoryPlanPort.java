package com.nammamedmate.inventory.application.port.out;

/** Growth+ plan gate for inventory online-visibility (Free/Starter locked). */
@FunctionalInterface
public interface InventoryPlanPort {

  boolean growthFeaturesEnabled();
}
