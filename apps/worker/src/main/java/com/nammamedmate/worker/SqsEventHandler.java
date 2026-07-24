package com.nammamedmate.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AWS Lambda entry point for the worker. Bootstraps the Spring context once in a static initializer
 * so SnapStart captures it at checkpoint (init-time, not lazy on first invoke). Each SQS record
 * body is handed to {@link SqsNoOpHandler}.
 */
public class SqsEventHandler implements RequestHandler<SQSEvent, Void> {

  private static final ConfigurableApplicationContext CONTEXT =
      new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE).run();

  @Override
  public Void handleRequest(SQSEvent input, Context context) {
    if (input == null) {
      return null;
    }
    SqsNoOpHandler handler = CONTEXT.getBean(SqsNoOpHandler.class);
    for (SQSMessage record : input.getRecords()) {
      if (record == null) {
        continue;
      }
      handler.handle(record.getBody());
    }
    return null;
  }
}
