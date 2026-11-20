/*
 * Copyright 2016, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openremote.container.message;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.DefaultErrorHandlerBuilder;
import org.apache.camel.component.snmp.SnmpComponent;
import org.apache.camel.health.HealthCheckRegistry;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.engine.DefaultStreamCachingStrategy;
import org.apache.camel.impl.health.ConsumersHealthCheckRepository;
import org.apache.camel.impl.health.ContextHealthCheck;
import org.apache.camel.impl.health.DefaultHealthCheckRegistry;
import org.apache.camel.impl.health.RoutesHealthCheckRepository;
import org.apache.camel.spi.ExecutorServiceManager;
import org.apache.camel.spi.StreamCachingStrategy;
import org.apache.camel.spi.ThreadPoolFactory;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.camel.support.DefaultRegistry;
import org.apache.camel.support.DefaultThreadPoolFactory;
import org.apache.camel.support.SimpleRegistry;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;

public class MessageBrokerService implements ContainerService {

  private static final Logger LOG = Logger.getLogger(MessageBrokerService.class.getName());
  public static final int PRIORITY = ContainerService.LOW_PRIORITY;

  protected DefaultCamelContext context = new DefaultCamelContext();
  protected ProducerTemplate producerTemplate = context.createProducerTemplate();
  protected FluentProducerTemplate fluentProducerTemplate = context.createFluentProducerTemplate();

  private volatile boolean isStarted;

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    final ExecutorServiceManager executorServiceManager = context.getExecutorServiceManager();

    // Not using InstrumentedThreadPoolFactory directly as it only uses the ThreadPoolProfile ID for
    // naming
    ThreadPoolFactory threadPoolFactory =
        new DefaultThreadPoolFactory() {
          @Override
          public ExecutorService newThreadPool(
              ThreadPoolProfile profile, ThreadFactory threadFactory) {

            ExecutorService executorService;

            // Force any endpoints that use the default profile to use the container executor to
            // reduce thread count
            if (profile.isDefaultProfile()) {
              executorService = container.getExecutor();
            } else {
              executorService = super.newThreadPool(profile, threadFactory);
            }

            return executorService;
          }

          @Override
          public ScheduledExecutorService newScheduledThreadPool(
              ThreadPoolProfile profile, ThreadFactory threadFactory) {
            ScheduledExecutorService scheduledExecutorService;

            // Force any endpoints that use the default profile to use a single built in executor to
            // avoid excessive thread creation
            if (profile.isDefaultProfile()) {
              scheduledExecutorService = container.getScheduledExecutor();
            } else {
              scheduledExecutorService = super.newScheduledThreadPool(profile, threadFactory);
            }

            return scheduledExecutorService;
          }
        };

    executorServiceManager.setThreadNamePattern("#name#-#counter#");
    executorServiceManager.setThreadPoolFactory(threadPoolFactory);

    // TODO might need this for errorhandler?
    context.setAllowUseOriginalMessage(false);

    // Don't use JMS, we do our own correlation
    context.setUseBreadcrumb(false);

    // Enable health checks - Have to manually add built in ones for some reason
    context.setLoadHealthChecks(true);
    final var checkRegistry = new DefaultHealthCheckRegistry();
    checkRegistry.setExposureLevel("full");
    checkRegistry.register(new RoutesHealthCheckRepository());
    checkRegistry.register(new ConsumersHealthCheckRepository());
    checkRegistry.register(new ContextHealthCheck());
    context.getCamelContextExtension().addContextPlugin(HealthCheckRegistry.class, checkRegistry);

    // Force a quick shutdown of routes with in-flight exchanges
    context.getShutdownStrategy().setTimeout(5);
    context.getShutdownStrategy().setSuppressLoggingOnTimeout(true);

    context.setStreamCaching(true);
    StreamCachingStrategy streamCachingStrategy = new DefaultStreamCachingStrategy();
    streamCachingStrategy.setSpoolThreshold(524288); // Half megabyte
    context.setStreamCachingStrategy(streamCachingStrategy);

    context.getCamelContextExtension().setErrorHandlerFactory(new DefaultErrorHandlerBuilder());

    if (container.isDevMode()) {
      context.setMessageHistory(true);
      context.setSourceLocationEnabled(true);
    }

    ((SimpleRegistry) ((DefaultRegistry) context.getRegistry()).getFallbackRegistry())
        .put("OpenRemote", Map.of(Container.class, container));

    context.addComponent("snmp", new SnmpComponent());
  }

  @Override
  public void start(Container container) throws Exception {
    LOG.info("Starting Camel message broker");
    context.start();
    isStarted = true;
  }

  @Override
  public void stop(Container container) throws Exception {
    // We want to indicate service is stopped before actually stopping it so callers will not be
    // able to push new events during shutdown
    isStarted = false;
    if (context != null) {
      context.stop();
    }
  }

  public boolean isStarted() {
    return isStarted;
  }

  public DefaultCamelContext getContext() {
    return context;
  }

  public ProducerTemplate getProducerTemplate() {
    return producerTemplate;
  }

  public FluentProducerTemplate getFluentProducerTemplate() {
    return fluentProducerTemplate;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" + '}';
  }
}
