package com.nammamedmate.inventory;

import com.nammamedmate.inventory.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InventoryConfig {

  @Bean
  @ConditionalOnMissingBean(InventoryPlanPort.class)
  InventoryPlanPort inventoryPlanPort(
      @Value("${medmate.inventory.growth-features-enabled:false}") boolean growthFeaturesEnabled) {
    return () -> growthFeaturesEnabled;
  }

  @Bean
  @ConditionalOnMissingBean(SimpleXlsxExporter.class)
  SimpleXlsxExporter simpleXlsxExporter() {
    return new SimpleXlsxExporter();
  }

  @Bean
  @ConditionalOnMissingBean(InventoryExcelExporter.class)
  InventoryExcelExporter inventoryExcelExporter(SimpleXlsxExporter exporter) {
    return exporter;
  }
}
