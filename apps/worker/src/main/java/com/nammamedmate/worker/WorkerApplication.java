package com.nammamedmate.worker;

import com.nammamedmate.automation.AutomationConfig;
import com.nammamedmate.crm.CrmConfig;
import com.nammamedmate.customer.CustomerConfig;
import com.nammamedmate.inventory.InventoryConfig;
import com.nammamedmate.marketing.MarketingConfig;
import com.nammamedmate.notification.NotificationConfig;
import com.nammamedmate.observability_ops.ObservabilityOpsConfig;
import com.nammamedmate.pharmacy.PharmacyConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan(
    basePackages = {
      "com.nammamedmate.worker",
      "com.nammamedmate.notification.adapter.in.messaging",
      "com.nammamedmate.notification.application",
      "com.nammamedmate.notification.adapter.out.persistence",
      "com.nammamedmate.pharmacy.adapter.in.messaging",
      "com.nammamedmate.pharmacy.application",
      "com.nammamedmate.pharmacy.adapter.out.persistence",
      "com.nammamedmate.customer.adapter.in.messaging",
      "com.nammamedmate.customer.application",
      "com.nammamedmate.customer.adapter.out.persistence",
      "com.nammamedmate.marketing.adapter.in.messaging",
      "com.nammamedmate.marketing.application",
      "com.nammamedmate.marketing.adapter.out.persistence",
      "com.nammamedmate.crm.adapter.in.messaging",
      "com.nammamedmate.support.adapter.in.messaging",
      "com.nammamedmate.automation.adapter.in.messaging",
      "com.nammamedmate.automation.application",
      "com.nammamedmate.automation.adapter.out.persistence",
      "com.nammamedmate.observability_ops.adapter.in.messaging",
      "com.nammamedmate.medicine_schedule.adapter.in.messaging",
      "com.nammamedmate.inventory.adapter.in.messaging"
    },
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*\\.(adapter\\.in\\.web|web)\\..*"))
@Import({
  NotificationConfig.class,
  CustomerConfig.class,
  PharmacyConfig.class,
  MarketingConfig.class,
  AutomationConfig.class,
  CrmConfig.class,
  ObservabilityOpsConfig.class,
  InventoryConfig.class
})
public class WorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
