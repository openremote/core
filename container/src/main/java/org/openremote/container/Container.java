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
package org.openremote.container;

import static java.lang.System.Logger.Level.*;
import static java.util.stream.StreamSupport.stream;
import static org.openremote.model.util.MapAccess.getBoolean;
import static org.openremote.model.util.MapAccess.getInteger;

import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.openremote.container.concurrent.ContainerScheduledExecutor;
import org.openremote.container.concurrent.ContainerThreadFactory;
import org.openremote.model.ContainerService;
import org.openremote.model.util.Config;
import org.openremote.model.util.MapAccess;
import org.openremote.model.util.ValueUtil;

/**
 * A thread-safe registry of {@link ContainerService}s.
 *
 * <p>Create the container with {@link ContainerService}s, then let it manage the life cycle of
 * these services.
 *
 * <p>Access environment configuration through {@link #getConfig()} and the helper methods in {@link
 * MapAccess}. Consider using {@link Config#OR_DEV_MODE} to distinguish between development and
 * production environments. To execute tasks in a standard way two {@link ExecutorService}s are
 * provided:
 *
 * <ul>
 *   <li>{@link #EXECUTOR} - A dynamic {@link ThreadPoolExecutor} with {@link
 *       #OR_EXECUTOR_THREADS_MIN} core threads and max pool size of {@link
 *       #OR_EXECUTOR_THREADS_MAX} for general task execution/li>
 *   <li>{@link #SCHEDULED_EXECUTOR} - A {@link ScheduledThreadPoolExecutor} which is a fixed thread
 *       pool of size {@link #OR_SCHEDULED_EXECUTOR_THREADS} for scheduled task execution
 * </ul>
 */
public class Container implements org.openremote.model.Container {

  public static final System.Logger LOG = System.getLogger(Container.class.getName());
  public static ScheduledExecutorService SCHEDULED_EXECUTOR;
  public static ExecutorService EXECUTOR;
  public static final String OR_SCHEDULED_EXECUTOR_THREADS = "OR_SCHEDULED_EXECUTOR_THREADS";
  public static final int OR_SCHEDULED_EXECUTOR_THREADS_DEFAULT =
      Math.min(Runtime.getRuntime().availableProcessors(), 4);
  public static final String OR_EXECUTOR_THREADS_MIN = "OR_EXECUTOR_THREADS_MIN";
  public static final String OR_EXECUTOR_THREADS_MAX = "OR_EXECUTOR_THREADS_MAX";
  public static final int OR_EXECUTOR_THREADS_MIN_DEFAULT =
      Math.min(Runtime.getRuntime().availableProcessors(), 8);
  public static final int OR_EXECUTOR_THREADS_MAX_DEFAULT =
      Runtime.getRuntime().availableProcessors() * 10;
  protected MeterRegistry meterRegistry;

  protected Thread waitingThread;
  protected volatile boolean running;
  protected final Map<Class<? extends ContainerService>, ContainerService> services =
      new LinkedHashMap<>();

  /**
   * Discover {@link ContainerService}s using {@link ServiceLoader}; services are then ordered by
   * {@link ContainerService#getPriority}.
   */
  public Container() {
    this(
        stream(ServiceLoader.load(ContainerService.class).spliterator(), false)
            .sorted(Comparator.comparingInt(ContainerService::getPriority))
            .collect(Collectors.toList()));
  }

  public Container(ContainerService... services) {
    this(null, Arrays.asList(services));
  }

  public Container(Iterable<ContainerService> services) {
    this(null, services);
  }

  public Container(Map<String, String> config, Iterable<ContainerService> services) {
    Config.init(config);

    boolean metricsEnabled =
        getBoolean(getConfig(), Config.OR_METRICS_ENABLED, Config.OR_METRICS_ENABLED_DEFAULT);
    LOG.log(INFO, "Metrics enabled: " + metricsEnabled);

    if (metricsEnabled) {
      // TODO: Add a meter registry provider SPI to make this pluggable
      meterRegistry =
          new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
              PrometheusConfig.DEFAULT, PrometheusRegistry.defaultRegistry, Clock.SYSTEM);
    }

    int scheduledExecutorThreads =
        getInteger(
            getConfig(), OR_SCHEDULED_EXECUTOR_THREADS, OR_SCHEDULED_EXECUTOR_THREADS_DEFAULT);

    int executorThreadsMin =
        getInteger(getConfig(), OR_EXECUTOR_THREADS_MIN, OR_EXECUTOR_THREADS_MIN_DEFAULT);
    int executorThreadsMax =
        getInteger(getConfig(), OR_EXECUTOR_THREADS_MAX, OR_EXECUTOR_THREADS_MAX_DEFAULT);

    SCHEDULED_EXECUTOR =
        new ContainerScheduledExecutor("ContainerScheduledExecutor", scheduledExecutorThreads);
    EXECUTOR =
        new ThreadPoolExecutor(
            executorThreadsMin,
            executorThreadsMax,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ContainerThreadFactory("ContainerExecutor"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    LOG.log(
        INFO,
        "Container executor threads min=" + executorThreadsMin + ", max=" + executorThreadsMax);
    LOG.log(INFO, "Container Scheduled executor threads=" + scheduledExecutorThreads);

    if (meterRegistry != null) {
      SCHEDULED_EXECUTOR =
          ExecutorServiceMetrics.monitor(
              meterRegistry, SCHEDULED_EXECUTOR, "ContainerScheduledExecutor");
      EXECUTOR = ExecutorServiceMetrics.monitor(meterRegistry, EXECUTOR, "ContainerExecutor");
    }

    // Any log handlers of the root logger that are container services must be registered
    for (Handler handler : Logger.getLogger("").getHandlers()) {
      if (handler instanceof ContainerService) {
        ContainerService containerServiceLogHandler = (ContainerService) handler;
        this.services.put(containerServiceLogHandler.getClass(), containerServiceLogHandler);
      }
    }

    if (services != null) {
      services.forEach(svc -> this.services.put(svc.getClass(), svc));
    }

    Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
  }

  @Override
  public Map<String, String> getConfig() {
    return Config.get();
  }

  public boolean isDevMode() {
    return Config.isDevMode();
  }

  public boolean isRunning() {
    return running;
  }

  public synchronized void start() throws Exception {
    if (isRunning()) return;
    LOG.log(INFO, ">>> Starting runtime container...");
    try {
      for (ContainerService service : getServices()) {
        LOG.log(INFO, "Initializing service: " + service.getClass().getName());
        service.init(Container.this);
      }

      // Initialize the asset model
      ValueUtil.initialise(this);

      if (isDevMode()) {
        ValueUtil.JSON.enable(SerializationFeature.INDENT_OUTPUT);
      }

      for (ContainerService service : getServices()) {
        LOG.log(INFO, "Starting service: " + service.getClass().getName());
        service.start(Container.this);
      }
    } catch (Exception ex) {
      LOG.log(ERROR, ">>> Runtime container startup failed", ex);
      stop(true);
      throw ex;
    }
    running = true;
    LOG.log(INFO, ">>> Runtime container startup complete");
  }

  public synchronized void stop() {
    stop(false);
  }

  private void stop(boolean force) {
    if (!force && !isRunning()) return;
    LOG.log(INFO, "<<< Stopping runtime container...");

    List<ContainerService> servicesToStop = Arrays.asList(getServices());
    Collections.reverse(servicesToStop);
    for (ContainerService service : servicesToStop) {
      LOG.log(INFO, "Stopping service: " + service.getClass().getName());
      try {
        service.stop(this);
      } catch (Exception e) {
        LOG.log(
            INFO, "Exception thrown while stopping service: " + service.getClass().getName(), e);
      }
    }

    try {
      LOG.log(INFO, "Canceling scheduled tasks");
      SCHEDULED_EXECUTOR.shutdown();
    } catch (Exception e) {
      LOG.log(WARNING, "Exception thrown while trying to stop scheduled tasks", e);
    }

    try {
      LOG.log(INFO, "Stopping executor");
      EXECUTOR.shutdown();
    } catch (Exception e) {
      LOG.log(WARNING, "Exception thrown while trying to stop executor", e);
    }

    if (meterRegistry != null) {
      Metrics.globalRegistry.remove(meterRegistry);
      PrometheusRegistry.defaultRegistry.clear();
    }
    meterRegistry = null;
    if (waitingThread != null) {
      waitingThread.interrupt();
    }
    waitingThread = null;
    running = false;
    LOG.log(INFO, "<<< Runtime container stopped");
  }

  /** Starts the container and a non-daemon thread that waits forever. */
  public synchronized void startBackground() throws Exception {
    start();
    if (waitingThread == null || !waitingThread.isAlive()) {
      waitingThread = startWaitingThread();
    }
  }

  static Thread startWaitingThread() {
    Thread thread =
        new Thread("Container Waiting") {
          @Override
          public void run() {
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
              // Ignore, thrown on shutdown
            }
          }
        };
    thread.setDaemon(false);
    thread.start();
    return thread;
  }

  @Override
  public ContainerService[] getServices() {
    synchronized (services) {
      return services.values().toArray(new ContainerService[0]);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends ContainerService> Collection<T> getServices(Class<T> type) {
    synchronized (services) {
      Set<T> result = new HashSet<>();
      for (ContainerService containerService : services.values()) {
        if (type.isAssignableFrom(containerService.getClass())) {
          result.add((T) containerService);
        }
      }
      return result;
    }
  }

  @Override
  public <T extends ContainerService> boolean hasService(Class<T> type) {
    return !getServices(type).isEmpty();
  }

  @Override
  public MeterRegistry getMeterRegistry() {
    return meterRegistry;
  }

  /**
   * Get a service instance matching the specified type exactly, or if that yields no result, try to
   * get the first service instance that has a matching interface.
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T extends ContainerService> T getService(Class<T> type) throws IllegalStateException {
    synchronized (services) {
      T service = (T) services.get(type);
      if (service == null) {
        for (ContainerService containerService : services.values()) {
          if (type.isAssignableFrom(containerService.getClass())) {
            service = (T) containerService;
            break;
          }
        }
      }
      if (service == null) throw new IllegalStateException("Missing required service: " + type);
      return service;
    }
  }

  @Override
  public ScheduledExecutorService getScheduledExecutor() {
    return SCHEDULED_EXECUTOR;
  }

  @Override
  public ExecutorService getExecutor() {
    return EXECUTOR;
  }
}
