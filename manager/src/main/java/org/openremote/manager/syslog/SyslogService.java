/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.manager.syslog;

import static java.time.temporal.ChronoUnit.DAYS;

import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.syslog.SyslogConfig;
import org.openremote.model.syslog.SyslogEvent;
import org.openremote.model.syslog.SyslogLevel;
import org.openremote.model.util.MapAccess;
import org.openremote.model.util.Pair;

/**
 * Act as a JUL handler, publishes (some) log messages on the client event bus, stores (some,
 * depending on {@link SyslogConfig}) log messages in the database.
 */
public class SyslogService extends Handler implements ContainerService {

  public static final String OR_SYSLOG_LOG_LEVEL = "OR_SYSLOG_LOG_LEVEL";
  public static final SyslogLevel OR_SYSLOG_LOG_LEVEL_DEFAULT = SyslogLevel.INFO;
  public static final String OR_SYSLOG_MAX_AGE_DAYS = "OR_SYSLOG_MAX_AGE_DAYS";
  public static final int OR_SYSLOG_MAX_AGE_DAYS_DEFAULT = 5;
  private static final Logger LOG = Logger.getLogger(SyslogService.class.getName());

  protected ScheduledExecutorService scheduledExecutorService;
  protected PersistenceService persistenceService;
  protected ClientEventService clientEventService;
  protected SyslogConfig config;

  protected final List<SyslogEvent> batch = new ArrayList<>();
  protected ScheduledFuture flushBatchFuture;
  protected ScheduledFuture deleteOldFuture;

  @Override
  public int getPriority() {
    return ContainerService.DEFAULT_PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    scheduledExecutorService = container.getScheduledExecutor();

    if (container.hasService(ClientEventService.class)
        && container.hasService(PersistenceService.class)) {
      LOG.info("Syslog service enabled");
      clientEventService = container.getService(ClientEventService.class);
      persistenceService = container.getService(PersistenceService.class);
    } else {
      LOG.info("Syslog service disabled, missing required services");
    }

    if (clientEventService != null) {
      clientEventService.addSubscriptionAuthorizer(
          (realm, auth, subscription) ->
              subscription.isEventType(SyslogEvent.class)
                  && auth != null
                  && (auth.isSuperUser()
                      || auth.hasResourceRole(
                          Constants.READ_LOGS_ROLE, Constants.KEYCLOAK_CLIENT_ID)));
    }

    if (container.hasService(ManagerWebService.class)) {
      container.getService(ManagerWebService.class).addApiSingleton(new SyslogResourceImpl(this));
    }

    int maxAgeDays =
        MapAccess.getInteger(
            container.getConfig(), OR_SYSLOG_MAX_AGE_DAYS, OR_SYSLOG_MAX_AGE_DAYS_DEFAULT);
    SyslogLevel syslogLevel =
        Optional.ofNullable(MapAccess.getString(container.getConfig(), OR_SYSLOG_LOG_LEVEL, null))
            .map(SyslogLevel::valueOf)
            .orElse(OR_SYSLOG_LOG_LEVEL_DEFAULT);
    config = new SyslogConfig(syslogLevel, SyslogCategory.values(), maxAgeDays * 24 * 60);
  }

  @Override
  public void start(Container container) throws Exception {
    if (persistenceService != null) {

      TimerService timerService = container.getService(TimerService.class);

      // Flush batch every 3 seconds (wait 10 seconds for database (schema) to be ready in dev mode)
      flushBatchFuture =
          scheduledExecutorService.scheduleAtFixedRate(this::flushBatch, 10, 3, TimeUnit.SECONDS);

      // Clear outdated events once a day
      if (config.getStoredMaxAgeMinutes() > 0) {
        // Schedule purge at approximately 3AM daily
        long initialDelay =
            ChronoUnit.MILLIS.between(
                timerService.getNow(),
                timerService.getNow().truncatedTo(DAYS).plus(27, ChronoUnit.HOURS));

        deleteOldFuture =
            scheduledExecutorService.scheduleAtFixedRate(
                this::purgeSyslog,
                initialDelay,
                Duration.ofDays(1).toMillis(),
                TimeUnit.MILLISECONDS);
      }
    }
  }

  protected void purgeSyslog() {
    try {
      persistenceService.doTransaction(
          em ->
              em.createQuery("delete from SyslogEvent e " + "where e.timestamp <= :date")
                  .setParameter(
                      "date",
                      Instant.now().minus(config.getStoredMaxAgeMinutes(), ChronoUnit.MINUTES))
                  .executeUpdate());
    } catch (Throwable e) {
      LOG.log(Level.WARNING, "Failed to purge syslog events", e);
    }
  }

  @Override
  public void stop(Container container) throws Exception {
    if (flushBatchFuture != null) {
      flushBatchFuture.cancel(true);
      flushBatchFuture = null;
    }
    if (deleteOldFuture != null) {
      deleteOldFuture.cancel(true);
      deleteOldFuture = null;
    }
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}

  @Override
  public void publish(LogRecord record) {
    SyslogEvent syslogEvent = SyslogCategory.mapSyslogEvent(record);
    if (syslogEvent != null) {
      try {
        store(syslogEvent);
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Failed to store syslog event", e);
      }
      try {
        if (clientEventService != null) clientEventService.publishEvent(syslogEvent);
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Failed to send syslog event to subscribed clients", e);
      }
    }
  }

  public void setConfig(SyslogConfig config) {
    synchronized (batch) {
      LOG.info("Using: " + config);
      this.config = config;
    }
  }

  public SyslogConfig getConfig() {
    synchronized (batch) {
      return config;
    }
  }

  public void clearStoredEvents() {
    if (persistenceService == null) return;
    synchronized (batch) {
      persistenceService.doTransaction(
          em -> em.createQuery("delete from SyslogEvent e").executeUpdate());
    }
  }

  public Pair<Long, List<SyslogEvent>> getEvents(
      SyslogLevel level,
      int perPage,
      int page,
      Instant from,
      Instant to,
      List<SyslogCategory> categories,
      List<String> subCategories) {
    if (persistenceService == null) return null;

    if (to == null) {
      to = Instant.now();
    }
    if (from == null) {
      from = to.minus(1, ChronoUnit.HOURS);
    }

    AtomicLong count = new AtomicLong();
    final Instant fromInstant = from;
    final Instant toInstant = to;

    List<SyslogEvent> events =
        persistenceService.doReturningTransaction(
            em -> {
              StringBuilder sb =
                  new StringBuilder(
                      "from SyslogEvent e where e.timestamp >= :from and e.timestamp <= :to");
              if (level != null) {
                sb.append(" and e.level >= :level");
              }
              if (categories != null && !categories.isEmpty()) {
                sb.append(" and e.category in :categories");
              }
              if (subCategories != null && !subCategories.isEmpty()) {
                sb.append(" and e.subCategory in :subCategories");
              }

              TypedQuery<Long> countQuery =
                  em.createQuery("select count(e.id) " + sb.toString(), Long.class);
              countQuery.setParameter("from", fromInstant);
              countQuery.setParameter("to", toInstant);
              if (level != null) {
                countQuery.setParameter("level", level);
              }
              if (categories != null && !categories.isEmpty()) {
                countQuery.setParameter("categories", categories);
              }
              if (subCategories != null && !subCategories.isEmpty()) {
                countQuery.setParameter("subCategories", subCategories);
              }
              count.set(countQuery.getSingleResult());

              if (count.get() == 0L) {
                return Collections.emptyList();
              }

              sb.append(" order by e.timestamp desc");
              TypedQuery<SyslogEvent> query =
                  em.createQuery("select e " + sb.toString(), SyslogEvent.class);

              query.setParameter("from", fromInstant);
              query.setParameter("to", toInstant);
              if (level != null) {
                query.setParameter("level", level);
              }
              if (categories != null && !categories.isEmpty()) {
                query.setParameter("categories", categories);
              }
              if (subCategories != null && !subCategories.isEmpty()) {
                query.setParameter("subCategories", subCategories);
              }

              query.setMaxResults(perPage);
              if (page > 1) {
                query.setFirstResult(((page - 1) * perPage) + 1);
              }
              return query.getResultList();
            });

    return new Pair<>(count.get(), events);
  }

  protected void store(SyslogEvent syslogEvent) {
    if (persistenceService == null) return;

    // If we are not ready (on startup), ignore
    if (persistenceService.getEntityManagerFactory() == null) {
      return;
    }
    boolean isLoggable =
        config.getStoredLevel().isLoggable(syslogEvent)
            && Arrays.asList(config.getStoredCategories()).contains(syslogEvent.getCategory());
    if (isLoggable) {
      synchronized (batch) {
        batch.add(syslogEvent);
      }
    }
  }

  protected void flushBatch() {
    if (persistenceService == null) return;
    synchronized (batch) {
      final List<SyslogEvent> transientEvents = new ArrayList<>(batch);
      batch.clear();
      if (transientEvents.size() == 0) return;
      LOG.finest("Flushing syslog batch: " + transientEvents.size());
      try {
        persistenceService.doTransaction(
            em -> {
              try {
                for (SyslogEvent e : transientEvents) {
                  em.persist(e);
                }
              } catch (RuntimeException ex) {
                // This is not a big problem, it may happen on shutdown of database connections
                // during tests, just inform the user
                // TODO Or is it a serious problem and we need to escalate? In any case, just
                // throwing the ex is not good
                LOG.info("Error flushing syslog to database, some events are lost: " + ex);
              } finally {
                em.flush();
              }
            });
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Exception occurred while flushing the syslog", e);
      }
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{}";
  }
}
