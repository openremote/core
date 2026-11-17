/*
 * Copyright 2026, OpenRemote Inc.
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
package org.openremote.manager.notification;

import static java.time.temporal.ChronoUnit.*;
import static java.util.Map.entry;
import static org.openremote.manager.notification.NotificationProcessingException.Reason.*;
import static org.openremote.model.notification.Notification.HEADER_SOURCE;
import static org.openremote.model.notification.Notification.Source.*;

import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.event.shared.EventSubscription;
import org.openremote.model.event.shared.RealmFilter;
import org.openremote.model.notification.Notification;
import org.openremote.model.notification.NotificationEvent;
import org.openremote.model.notification.NotificationSendResult;
import org.openremote.model.notification.RepeatFrequency;
import org.openremote.model.notification.SentNotification;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.security.User;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.TimeUtil;

public class NotificationService extends RouteBuilder implements ContainerService {

  public static final String NOTIFICATION_QUEUE = "direct://NotificationQueue";
  private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());
  protected TimerService timerService;
  protected PersistenceService persistenceService;
  protected AssetStorageService assetStorageService;
  protected ManagerIdentityService identityService;
  protected MessageBrokerService messageBrokerService;
  protected ExecutorService executorService;
  protected ClientEventService clientEventService;
  protected Map<String, NotificationHandler> notificationHandlerMap = new HashMap<>();

  @Override
  public int getPriority() {
    return ContainerService.DEFAULT_PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    this.timerService = container.getService(TimerService.class);
    this.persistenceService = container.getService(PersistenceService.class);
    this.assetStorageService = container.getService(AssetStorageService.class);
    this.clientEventService = container.getService(ClientEventService.class);
    this.identityService = container.getService(ManagerIdentityService.class);
    this.messageBrokerService = container.getService(MessageBrokerService.class);
    executorService = container.getExecutor();
    container.getService(MessageBrokerService.class).getContext().addRoutes(this);

    container
        .getServices(NotificationHandler.class)
        .forEach(
            notificationHandler ->
                notificationHandlerMap.put(notificationHandler.getTypeName(), notificationHandler));

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new NotificationResourceImpl(
                timerService,
                container.getService(ManagerIdentityService.class),
                this,
                container.getService(MessageBrokerService.class),
                container.getService(AssetStorageService.class)));
    clientEventService.addSubscriptionAuthorizer(
        (realm, authContext, eventSubscription) -> {
          if (!eventSubscription.isEventType(NotificationEvent.class) || authContext == null) {
            return false;
          }

          if (!authContext.isSuperUser()) {
            @SuppressWarnings("unchecked")
            EventSubscription<NotificationEvent> subscription =
                (EventSubscription<NotificationEvent>) eventSubscription;
            subscription.setFilter(new RealmFilter<>(authContext.getAuthenticatedRealmName()));
          }

          return true;
        });
  }

  @Override
  public void start(Container container) throws Exception {}

  @Override
  public void stop(Container container) throws Exception {}

  @Override
  public void configure() throws Exception {

    from(NOTIFICATION_QUEUE)
        .routeId("NotificationQueue")
        .threads()
        .executorService(executorService)
        .process(
            exchange -> {
              Notification notification = exchange.getIn().getBody(Notification.class);

              if (notification == null) {
                throw new NotificationProcessingException(
                    MISSING_NOTIFICATION, "Notification must be set");
              }

              LOG.finest("Processing: " + notification.getName());

              if (notification.getMessage() == null) {
                throw new NotificationProcessingException(
                    MISSING_CONTENT, "Notification message must be set");
              }

              Notification.Source source =
                  exchange.getIn().getHeader(HEADER_SOURCE, () -> null, Notification.Source.class);

              if (source == null) {
                throw new NotificationProcessingException(MISSING_SOURCE);
              }

              // Validate handler and message
              NotificationHandler handler =
                  notificationHandlerMap.get(notification.getMessage().getType());
              if (handler == null) {
                throw new NotificationProcessingException(
                    UNSUPPORTED_MESSAGE_TYPE,
                    "No handler for message type: " + notification.getMessage().getType());
              }
              if (!handler.isValid()) {
                throw new NotificationProcessingException(
                    NOTIFICATION_HANDLER_CONFIG_ERROR,
                    "Handler is not valid: " + handler.getTypeName());
              }
              if (!handler.isMessageValid(notification.getMessage())) {
                throw new NotificationProcessingException(INVALID_MESSAGE);
              }

              // Validate access and map targets to handler compatible targets
              String realm = null;
              String userId = null;
              String assetId = null;
              AtomicReference<String> sourceId = new AtomicReference<>("");
              boolean isSuperUser = false;
              boolean isRestrictedUser = false;
              AtomicReference<String> notificationRealm = new AtomicReference<>();

              switch (source) {
                case INTERNAL -> {
                  isSuperUser = true;
                  notificationRealm.set(Constants.MASTER_REALM);
                }
                case CLIENT -> {
                  AuthContext authContext =
                      exchange.getIn().getHeader(Constants.AUTH_CONTEXT, AuthContext.class);
                  if (authContext == null) {
                    // Anonymous clients cannot send notifications
                    throw new NotificationProcessingException(INSUFFICIENT_ACCESS);
                  }
                  // Client notifications are scoped to the caller's authenticated realm
                  realm = authContext.getAuthenticatedRealmName();
                  notificationRealm.set(realm);
                  userId = authContext.getUserId();
                  sourceId.set(userId);
                  isSuperUser = authContext.isSuperUser();
                  isRestrictedUser =
                      identityService.getIdentityProvider().isRestrictedUser(authContext);
                }
                case GLOBAL_RULESET -> {
                  isSuperUser = true;
                  // Global rulesets are realm-less, so unresolvable targets are stored under the
                  // master realm
                  notificationRealm.set(Constants.MASTER_REALM);
                }
                case REALM_RULESET -> {
                  realm = exchange.getIn().getHeader(Notification.HEADER_SOURCE_ID, String.class);
                  notificationRealm.set(realm);
                  sourceId.set(realm);
                }
                case ASSET_RULESET -> {
                  assetId = exchange.getIn().getHeader(Notification.HEADER_SOURCE_ID, String.class);
                  sourceId.set(assetId);
                  Asset<?> asset = assetStorageService.find(assetId, false);
                  realm = asset.getRealm();
                  notificationRealm.set(realm);
                }
              }

              LOG.fine(
                  "Sending "
                      + notification.getMessage().getType()
                      + " notification '"
                      + notification.getName()
                      + "': '"
                      + source
                      + ":"
                      + sourceId.get()
                      + "' -> "
                      + notification.getTargets());

              // Check access permissions
              checkAccess(
                  source,
                  sourceId.get(),
                  notification.getTargets(),
                  realm,
                  userId,
                  isSuperUser,
                  isRestrictedUser,
                  assetId);

              // Get the list of notification targets
              List<Notification.Target> mappedTargetsList =
                  handler.getTargets(
                      source, sourceId.get(), notification.getTargets(), notification.getMessage());

              if (mappedTargetsList == null || mappedTargetsList.isEmpty()) {
                throw new NotificationProcessingException(
                    MISSING_TARGETS, "Notification targets must be set");
              } else if (LOG.isLoggable(Level.FINER)) {
                LOG.finer(
                    "Notification targets mapped from: ["
                        + (notification.getTargets() != null
                            ? notification.getTargets().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(","))
                            : "null")
                        + "to: ["
                        + mappedTargetsList.stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(","))
                        + "]");
              }

              // Filter targets based on repeat frequency
              if (!TextUtil.isNullOrEmpty(notification.getName())
                  && (!TextUtil.isNullOrEmpty(notification.getRepeatInterval())
                      || notification.getRepeatFrequency() != null)) {
                mappedTargetsList =
                    mappedTargetsList.stream()
                        .filter(
                            target ->
                                okToSendNotification(source, sourceId.get(), target, notification))
                        .collect(Collectors.toList());
              }

              // Send message to each applicable target
              AtomicReference<Exception> error = new AtomicReference<>();

              // As we can have multiple targets in a single exchange we'll track the first
              // exception that occurs
              mappedTargetsList.forEach(
                  target -> {
                    Exception notificationError =
                        persistenceService.doReturningTransaction(
                            em -> {

                              // commit the notification first to get the ID
                              SentNotification sentNotification =
                                  new SentNotification()
                                      .setName(notification.getName())
                                      .setType(notification.getMessage().getType())
                                      .setSource(source)
                                      .setSourceId(sourceId.get())
                                      .setTarget(target.getType())
                                      .setTargetId(target.getId())
                                      .setMessage(notification.getMessage())
                                      .setRealm(resolveTargetRealm(target, notificationRealm.get()))
                                      .setSentOn(timerService.getNow());

                              sentNotification = em.merge(sentNotification);
                              long id = sentNotification.getId();

                              try {
                                handler.sendMessage(
                                    id, source, sourceId.get(), target, notification.getMessage());

                                NotificationSendResult result = NotificationSendResult.success();
                                LOG.fine("Notification sent '" + id + "': " + target);

                                // Merge the sent notification again with the message included just
                                // in case the handler modified the message
                                sentNotification.setMessage(notification.getMessage());

                              } catch (Exception e) {
                                NotificationProcessingException notificationProcessingException;
                                if (e instanceof NotificationProcessingException) {
                                  notificationProcessingException =
                                      (NotificationProcessingException) e;
                                } else {
                                  notificationProcessingException =
                                      new NotificationProcessingException(
                                          SEND_FAILURE, e.getMessage());
                                }
                                LOG.warning(
                                    "Notification failed '"
                                        + id
                                        + "': "
                                        + target
                                        + ", reason="
                                        + notificationProcessingException);
                                sentNotification.setError(
                                    TextUtil.isNullOrEmpty(
                                            notificationProcessingException.getMessage())
                                        ? "Unknown error"
                                        : notificationProcessingException.getMessage());
                                return notificationProcessingException;
                              } finally {
                                em.merge(sentNotification);
                              }
                              return null;
                            });

                    if (notificationError != null && error.get() == null) {
                      error.set(notificationError);
                    }
                  });

              exchange.getMessage().setBody(error.get() == null);
              if (error.get() != null) {
                throw error.get();
              }
            })
        .onException(Exception.class)
        .logStackTrace(false)
        .handled(true)
        .process(
            exchange -> {
              // Log the failure reason; notify sender in case of RequestReply
              Exception exception =
                  exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
              if (exception instanceof NotificationProcessingException) {
                LOG.warning("Notification processing failed: " + exception.getMessage());
              } else {
                LOG.log(Level.WARNING, "Notification processing failed", exception);
              }
              exchange.getMessage().setBody(false);
            });
  }

  /**
   * Resolves the realm a {@link SentNotification} should be stored against from its target so
   * cross-realm notifications are visible in the recipient's realm; falls back to the sender's
   * realm when the target cannot be resolved (e.g. {@link Notification.TargetType#CUSTOM} targets).
   */
  protected String resolveTargetRealm(Notification.Target target, String fallbackRealm) {
    switch (target.getType()) {
      case REALM:
        return target.getId();
      case USER:
        User user = identityService.getIdentityProvider().getUser(target.getId());
        if (user != null && user.getRealm() != null) return user.getRealm();
        break;
      case ASSET:
        Asset<?> asset = assetStorageService.find(target.getId(), false);
        if (asset != null) return asset.getRealm();
        break;
    }
    return fallbackRealm != null ? fallbackRealm : Constants.MASTER_REALM;
  }

  public boolean sendNotification(Notification notification) {
    return sendNotification(notification, INTERNAL, "");
  }

  public void sendNotificationAsync(
      Notification notification, Notification.Source source, String sourceId) {
    Map<String, Object> headers =
        Map.ofEntries(
            entry(Notification.HEADER_SOURCE, source),
            entry(Notification.HEADER_SOURCE_ID, sourceId));
    messageBrokerService
        .getFluentProducerTemplate()
        .withBody(notification)
        .withHeaders(headers)
        .to(NotificationService.NOTIFICATION_QUEUE)
        .send();
  }

  public boolean sendNotification(
      Notification notification, Notification.Source source, String sourceId) {
    Map<String, Object> headers =
        Map.ofEntries(
            entry(Notification.HEADER_SOURCE, source),
            entry(Notification.HEADER_SOURCE_ID, sourceId));
    return messageBrokerService
        .getFluentProducerTemplate()
        .withBody(notification)
        .withHeaders(headers)
        .to(NotificationService.NOTIFICATION_QUEUE)
        .request(Boolean.class);
  }

  /**
   * Marks a sent notification as delivered. Callers are responsible for enforcing realm
   * authorization.
   */
  public void setNotificationDelivered(long id) {
    setNotificationDelivered(id, timerService.getCurrentTimeMillis());
  }

  public void setNotificationDelivered(long id, long timestamp) {
    persistenceService.doTransaction(
        entityManager -> {
          Query query =
              entityManager.createQuery(
                  "UPDATE SentNotification SET deliveredOn=:timestamp WHERE id =:id");
          query.setParameter("id", id);
          query.setParameter("timestamp", Instant.ofEpochMilli(timestamp));
          query.executeUpdate();
        });
  }

  /**
   * Marks a sent notification as acknowledged. Callers are responsible for enforcing realm
   * authorization.
   */
  public void setNotificationAcknowledged(long id, String acknowledgement) {
    setNotificationAcknowledged(id, acknowledgement, timerService.getCurrentTimeMillis());
  }

  public void setNotificationAcknowledged(long id, String acknowledgement, long timestamp) {
    persistenceService.doTransaction(
        entityManager -> {
          Query query =
              entityManager.createQuery(
                  "UPDATE SentNotification SET acknowledgedOn=:timestamp, acknowledgement=:acknowledgement WHERE id =:id");
          query.setParameter("id", id);
          query.setParameter("timestamp", Instant.ofEpochMilli(timestamp));
          query.setParameter("acknowledgement", acknowledgement);
          query.executeUpdate();
        });
  }

  /**
   * Retrieves the details of an existing sent notification. Callers are responsible for enforcing
   * realm authorization.
   */
  public SentNotification getSentNotification(Long notificationId) {
    return persistenceService.doReturningTransaction(
        em -> em.find(SentNotification.class, notificationId));
  }

  /**
   * Retrieves sent notifications matching the criteria. Callers are responsible for enforcing realm
   * authorization; the auth context only narrows the results to what a restricted caller may see.
   */
  public List<SentNotification> getNotifications(
      List<Long> ids,
      List<String> types,
      Instant from,
      Instant to,
      List<String> realmIds,
      List<String> userIds,
      List<String> assetIds,
      Notification.Source source,
      SentNotification.SortField sort,
      boolean descending,
      Integer offset,
      Integer limit,
      AuthContext authContext)
      throws IllegalArgumentException {
    StringBuilder builder = new StringBuilder("select n from SentNotification n where 1=1");
    List<Object> parameters = new ArrayList<>();
    buildNotificationCriteria(
        builder,
        parameters,
        ids,
        types,
        from,
        to,
        realmIds,
        userIds,
        assetIds,
        source,
        authContext);
    builder.append(buildOrderBy(sort, descending));

    return persistenceService.doReturningTransaction(
        entityManager -> {
          TypedQuery<SentNotification> query =
              entityManager.createQuery(builder.toString(), SentNotification.class);
          IntStream.rangeClosed(1, parameters.size())
              .forEach(i -> query.setParameter(i, parameters.get(i - 1)));
          if (offset != null && offset > 0) {
            query.setFirstResult(offset);
          }
          if (limit != null && limit > 0) {
            query.setMaxResults(limit);
          }
          return query.getResultList();
        });
  }

  /**
   * Counts sent notifications matching the criteria. Callers are responsible for enforcing realm
   * authorization; the auth context only narrows the count to what a restricted caller may see.
   */
  public long getNotificationsCount(
      List<String> types,
      Instant from,
      Instant to,
      List<String> realmIds,
      List<String> userIds,
      List<String> assetIds,
      Notification.Source source,
      AuthContext authContext)
      throws IllegalArgumentException {
    StringBuilder builder = new StringBuilder("select count(n) from SentNotification n where 1=1");
    List<Object> parameters = new ArrayList<>();
    buildNotificationCriteria(
        builder,
        parameters,
        null,
        types,
        from,
        to,
        realmIds,
        userIds,
        assetIds,
        source,
        authContext);

    return persistenceService.doReturningTransaction(
        entityManager -> {
          TypedQuery<Long> query = entityManager.createQuery(builder.toString(), Long.class);
          IntStream.rangeClosed(1, parameters.size())
              .forEach(i -> query.setParameter(i, parameters.get(i - 1)));
          return query.getSingleResult();
        });
  }

  /**
   * Builds the {@code order by} clause for {@link #getNotifications}. Each {@link
   * SentNotification.SortField} maps to a fixed persisted column (never a caller-supplied string)
   * so ordering is injection-safe; a null sort keeps the default of newest-sent first. Status
   * orders by the derived sent/delivered/error state. Every ordering ends with an id tie-breaker so
   * paginated requests return a stable sequence.
   */
  protected String buildOrderBy(SentNotification.SortField sort, boolean descending) {
    String direction = sort == null || descending ? " desc" : " asc";
    String orderBy =
        switch (sort == null ? SentNotification.SortField.SENT_ON : sort) {
          case TITLE -> " order by n.name" + direction;
          case SOURCE -> " order by n.source" + direction;
          // sent (0) sorts before delivered (1) before error (2) when ascending
          case STATUS ->
              " order by case when n.error is not null then 2 when n.deliveredOn is not null then 1 else 0 end"
                  + direction;
          case SENT_ON -> " order by n.sentOn" + direction;
          case DELIVERED_ON -> " order by n.deliveredOn" + direction;
        };
    // Secondary id sort keeps pagination stable when rows tie on the sorted column
    return orderBy + ", n.id" + direction;
  }

  /**
   * Appends the shared filter criteria used by {@link #getNotifications} and {@link
   * #getNotificationsCount}. Restricted callers are additionally limited to notifications they
   * sent, that target them, or that target their realm; any other caller with access sees all
   * notifications matching the criteria.
   */
  protected void buildNotificationCriteria(
      StringBuilder builder,
      List<Object> parameters,
      List<Long> ids,
      List<String> types,
      Instant from,
      Instant to,
      List<String> realmIds,
      List<String> userIds,
      List<String> assetIds,
      Notification.Source source,
      AuthContext authContext) {
    int idx = 1;

    if (ids != null && !ids.isEmpty()) {
      builder.append(" AND n.id IN ?").append(idx++);
      parameters.add(ids);
    }
    if (types != null && !types.isEmpty()) {
      builder.append(" AND n.type IN ?").append(idx++);
      parameters.add(types);
    }
    if (from != null) {
      builder.append(" AND n.sentOn >= ?").append(idx++);
      parameters.add(from);
    }
    if (to != null) {
      builder.append(" AND n.sentOn <= ?").append(idx++);
      parameters.add(to);
    }
    if (source != null) {
      builder.append(" AND n.source = ?").append(idx++);
      parameters.add(source);
    }
    if (realmIds != null && !realmIds.isEmpty()) {
      builder.append(" AND n.realm IN ?").append(idx++);
      parameters.add(realmIds);
    }
    if (assetIds != null && !assetIds.isEmpty()) {
      builder
          .append(" AND n.target = ?")
          .append(idx++)
          .append(" AND n.targetId IN ?")
          .append(idx++);
      parameters.add(Notification.TargetType.ASSET);
      parameters.add(assetIds);
    } else if (userIds != null && !userIds.isEmpty()) {
      builder
          .append(" AND n.target = ?")
          .append(idx++)
          .append(" AND n.targetId IN ?")
          .append(idx++);
      parameters.add(Notification.TargetType.USER);
      parameters.add(userIds);
    }

    // Only restricted users are limited to notifications they sent, that target them, or that
    // target their realm;
    // any other caller with access (read:notifications / superuser) sees all of them.
    if (authContext != null
        && identityService.getIdentityProvider().isRestrictedUser(authContext)) {
      builder
          .append(" AND (n.sourceId = ?")
          .append(idx++)
          .append(" OR (n.target = ?")
          .append(idx++)
          .append(" AND n.targetId = ?")
          .append(idx++)
          .append(")")
          .append(" OR (n.target = ?")
          .append(idx++)
          .append(" AND n.targetId = ?")
          .append(idx++)
          .append("))");
      parameters.add(authContext.getUserId());
      parameters.add(Notification.TargetType.USER);
      parameters.add(authContext.getUserId());
      parameters.add(Notification.TargetType.REALM);
      parameters.add(authContext.getAuthenticatedRealmName());
    }
  }

  /**
   * Removes an existing sent notification. Callers are responsible for enforcing realm
   * authorization.
   */
  public void removeNotification(Long id) {
    persistenceService.doTransaction(
        entityManager ->
            entityManager
                .createQuery("delete SentNotification where id = :id")
                .setParameter("id", id)
                .executeUpdate());
  }

  /**
   * Removes existing sent notifications matching the criteria; a non null realm confines the delete
   * to that realm and at least one other criterion is required. Callers are responsible for
   * enforcing realm authorization, including that the notification, user and asset are accessible
   * to the requester.
   */
  public void removeNotifications(
      Long id, String type, Instant from, Instant to, String realm, String userId, String assetId)
      throws IllegalArgumentException {

    if (id == null
        && type == null
        && from == null
        && to == null
        && userId == null
        && assetId == null) {
      LOG.fine("No filters set for remove notifications request so not allowed");
      throw new IllegalArgumentException("No criteria specified");
    }

    StringBuilder builder = new StringBuilder("delete from SentNotification n where 1=1");
    List<Object> parameters = new ArrayList<>();

    if (id != null) {
      builder.append(" AND n.id = ?").append(parameters.size() + 1);
      parameters.add(id);
    }
    if (type != null) {
      builder.append(" AND n.type = ?").append(parameters.size() + 1);
      parameters.add(type);
    }
    if (from != null) {
      builder.append(" AND n.sentOn >= ?").append(parameters.size() + 1);
      parameters.add(from);
    }
    if (to != null) {
      builder.append(" AND n.sentOn <= ?").append(parameters.size() + 1);
      parameters.add(to);
    }
    if (realm != null) {
      builder.append(" AND n.realm = ?").append(parameters.size() + 1);
      parameters.add(realm);
    }
    if (assetId != null) {
      builder
          .append(" AND n.target = ?")
          .append(parameters.size() + 1)
          .append(" AND n.targetId = ?")
          .append(parameters.size() + 2);
      parameters.add(Notification.TargetType.ASSET);
      parameters.add(assetId);
    } else if (userId != null) {
      builder
          .append(" AND n.target = ?")
          .append(parameters.size() + 1)
          .append(" AND n.targetId = ?")
          .append(parameters.size() + 2);
      parameters.add(Notification.TargetType.USER);
      parameters.add(userId);
    }

    persistenceService.doTransaction(
        entityManager -> {
          Query query = entityManager.createQuery(builder.toString());
          IntStream.rangeClosed(1, parameters.size())
              .forEach(i -> query.setParameter(i, parameters.get(i - 1)));
          query.executeUpdate();
        });
  }

  protected Instant getRepeatAfterTimestamp(Notification notification, Instant lastSend) {
    Instant timestamp = null;

    if (TextUtil.isNullOrEmpty(notification.getName())) {
      return null;
    }

    if (notification.getRepeatFrequency() != null) {

      switch (notification.getRepeatFrequency()) {
        case HOURLY -> timestamp = lastSend.truncatedTo(HOURS).plus(1, HOURS);
        case DAILY -> timestamp = lastSend.truncatedTo(DAYS).plus(1, DAYS);
        case WEEKLY -> timestamp = lastSend.truncatedTo(WEEKS).plus(1, WEEKS);
        case MONTHLY -> timestamp = lastSend.truncatedTo(MONTHS).plus(1, MONTHS);
        case ANNUALLY -> timestamp = lastSend.truncatedTo(YEARS).plus(1, YEARS);
      }
    } else if (!TextUtil.isNullOrEmpty(notification.getRepeatInterval())) {
      timestamp =
          lastSend.plus(
              TimeUtil.parseTimeDuration(notification.getRepeatInterval()), ChronoUnit.MILLIS);
    }

    return timestamp;
  }

  protected void checkAccess(
      Notification.Source source,
      String sourceId,
      List<Notification.Target> targets,
      String realm,
      String userId,
      boolean isSuperUser,
      boolean isRestrictedUser,
      String assetId)
      throws NotificationProcessingException {

    if (isSuperUser) {
      return;
    }

    if (targets == null || targets.isEmpty()) {
      return;
    }

    targets.forEach(
        target -> {
          switch (target.getType()) {
            case REALM:
              if (source == CLIENT || source == ASSET_RULESET) {
                throw new NotificationProcessingException(INSUFFICIENT_ACCESS);
              }
            case USER:
              if (TextUtil.isNullOrEmpty(realm) || isRestrictedUser) {
                throw new NotificationProcessingException(INSUFFICIENT_ACCESS);
              }

              // Requester must be in the same realm as all target users
              boolean realmMatch;

              if (target.getType() == Notification.TargetType.USER) {
                User[] targetUsers =
                    identityService
                        .getIdentityProvider()
                        .queryUsers(
                            // Exclude service accounts and system accounts
                            new UserQuery()
                                .ids(target.getId())
                                .serviceUsers(false)
                                .attributes(
                                    new UserQuery.AttributeValuePredicate(
                                        true, new StringPredicate(User.SYSTEM_ACCOUNT_ATTRIBUTE))));
                if (targetUsers.length == 0) {
                  throw new NotificationProcessingException(
                      INSUFFICIENT_ACCESS,
                      "User targets must exist and must not be service or system accounts");
                }
                realmMatch =
                    Arrays.stream(targetUsers).allMatch(user -> realm.equals(user.getRealm()));
              } else {
                // Can only send to the same realm as the requester realm
                realmMatch = realm.equals(target.getId());
              }

              if (!realmMatch) {
                throw new NotificationProcessingException(
                    INSUFFICIENT_ACCESS, "Targets must all be in the same realm as the requester");
              }
              if (source == ASSET_RULESET && target.getType() == Notification.TargetType.USER) {
                if (!NotificationScope.isUserInAssetScope(
                    realm,
                    target.getId(),
                    assetId,
                    identityService.getIdentityProvider(),
                    assetStorageService)) {
                  throw new NotificationProcessingException(
                      INSUFFICIENT_ACCESS,
                      "Restricted user targets must be directly linked to the rule's scoped asset");
                }
              }
              break;

            case ASSET:
              if (TextUtil.isNullOrEmpty(realm)) {
                throw new NotificationProcessingException(INSUFFICIENT_ACCESS);
              }

              // If requestor is restricted user check all target assets are linked to that user
              if (isRestrictedUser
                  && !assetStorageService.isUserAssets(
                      userId, Collections.singletonList(target.getId()))) {
                throw new NotificationProcessingException(
                    INSUFFICIENT_ACCESS,
                    "Targets must all be linked to the requesting restricted user");
              }

              // Target assets must be in the same realm as requester
              if (!assetStorageService.isRealmAssets(
                  realm, Collections.singletonList(target.getId()))) {
                throw new NotificationProcessingException(
                    INSUFFICIENT_ACCESS, "Targets must all be in the same realm as the requestor");
              }

              // Target assets must be descendants of the requesting asset
              if (!TextUtil.isNullOrEmpty(assetId)) {
                if (!assetStorageService.isDescendantAssets(
                    assetId, Collections.singletonList(target.getId()))) {
                  throw new NotificationProcessingException(
                      INSUFFICIENT_ACCESS,
                      "Targets must all be descendants of the requesting asset");
                }
              }
              break;
          }
        });
  }

  protected boolean okToSendNotification(
      Notification.Source source,
      String sourceId,
      Notification.Target target,
      Notification notification) {

    if (notification.getRepeatFrequency() == RepeatFrequency.ALWAYS) {
      return true;
    }

    Instant lastSend =
        persistenceService
            .doReturningTransaction(
                entityManager ->
                    entityManager
                        .createQuery(
                            "SELECT n.sentOn FROM SentNotification n WHERE n.source =:source AND n.sourceId =:sourceId AND n.target =:target AND n.targetId =:targetId AND n.name =:name ORDER BY n.sentOn DESC",
                            Instant.class)
                        .setParameter("source", source)
                        .setParameter("sourceId", sourceId)
                        .setParameter("target", target.getType())
                        .setParameter("targetId", target.getId())
                        .setParameter("name", notification.getName())
                        .setMaxResults(1)
                        .getResultList())
            .stream()
            .findFirst()
            .orElse(null);

    return lastSend == null
        || (notification.getRepeatFrequency() != RepeatFrequency.ONCE
            && timerService
                .getNow()
                .plusSeconds(1)
                .isAfter(getRepeatAfterTimestamp(notification, lastSend)));
  }
}
