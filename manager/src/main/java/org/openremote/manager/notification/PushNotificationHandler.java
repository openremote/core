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

import static org.openremote.manager.gateway.GatewayService.isNotForGateway;
import static org.openremote.model.notification.PushNotificationMessage.TargetType.*;
import static org.openremote.model.security.User.PUSH_NOTIFICATIONS_DISABLED_ATTRIBUTE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.impl.ConsoleAsset;
import org.openremote.model.console.ConsoleProvider;
import org.openremote.model.notification.AbstractNotificationMessage;
import org.openremote.model.notification.Notification;
import org.openremote.model.notification.PushNotificationMessage;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.*;
import org.openremote.model.security.User;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

@SuppressWarnings("deprecation")
public class PushNotificationHandler extends RouteBuilder implements NotificationHandler {

  private static final Logger LOG = Logger.getLogger(PushNotificationHandler.class.getName());
  public static final String OR_FIREBASE_CONFIG_FILE = "OR_FIREBASE_CONFIG_FILE";
  public static final int CONNECT_TIMEOUT_MILLIS = 3000;
  public static final int READ_TIMEOUT_MILLIS = 3000;
  public static final String FCM_PROVIDER_NAME = "fcm";

  protected ManagerIdentityService managerIdentityService;
  protected AssetStorageService assetStorageService;
  protected GatewayService gatewayService;
  protected boolean valid;
  protected boolean devMode;
  protected Map<String, String> consoleFCMTokenMap = new ConcurrentHashMap<>();
  protected Set<String> fcmTokenBlacklist = Collections.synchronizedSet(new HashSet<>());

  @Override
  public int getPriority() {
    return ContainerService.DEFAULT_PRIORITY;
  }

  public void init(Container container) throws Exception {
    this.managerIdentityService = container.getService(ManagerIdentityService.class);
    this.assetStorageService = container.getService(AssetStorageService.class);
    this.gatewayService = container.getService(GatewayService.class);
    this.devMode = container.isDevMode();
    container.getService(MessageBrokerService.class).getContext().addRoutes(this);

    String firebaseConfigFilePath = container.getConfig().get(OR_FIREBASE_CONFIG_FILE);

    if (TextUtil.isNullOrEmpty(firebaseConfigFilePath)) {
      LOG.warning(OR_FIREBASE_CONFIG_FILE + " not defined, can not send FCM notifications");
      return;
    }

    if (!Files.isReadable(Paths.get(firebaseConfigFilePath))) {
      LOG.warning(
          OR_FIREBASE_CONFIG_FILE
              + " invalid path or file not readable: "
              + firebaseConfigFilePath);
      return;
    }

    try (InputStream is = Files.newInputStream(Paths.get(firebaseConfigFilePath))) {
      FirebaseOptions options =
          new FirebaseOptions.Builder()
              .setCredentials(GoogleCredentials.fromStream(is))
              .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
              .setReadTimeout(READ_TIMEOUT_MILLIS)
              .build();

      FirebaseApp.initializeApp(options);
      valid = true;
    } catch (Exception ex) {
      LOG.severe("Exception occurred whilst initialising FCM");
    }
  }

  @Override
  public void start(Container container) throws Exception {

    if (!isValid()) {
      LOG.warning("FCM configuration invalid so cannot start");
      return;
    }

    // Not using Collectors.toMap as there is a quirk in there which means null values aren't
    // supported!
    consoleFCMTokenMap = new HashMap<>();

    // Find all console assets that use this adapter
    assetStorageService
        .findAll(
            new AssetQuery()
                .select(
                    new AssetQuery.Select().attributes(ConsoleAsset.CONSOLE_PROVIDERS.getName()))
                .types(ConsoleAsset.class)
                .attributes(
                    new AttributePredicate(
                        ConsoleAsset.CONSOLE_PROVIDERS,
                        new ValueEmptyPredicate().negate(true),
                        false,
                        new NameValuePredicate.Path(
                            PushNotificationMessage.TYPE, "data", "token"))))
        .stream()
        .map(asset -> (ConsoleAsset) asset)
        .filter(PushNotificationHandler::isLinkedToFcmProvider)
        .forEach(asset -> consoleFCMTokenMap.put(asset.getId(), getFcmToken(asset).orElse(null)));
  }

  @Override
  public void stop(Container container) throws Exception {}

  @Override
  public void configure() throws Exception {
    // If any console asset was modified in the database, detect push provider changes
    from(PersistenceService.PERSISTENCE_TOPIC)
        .routeId("Persistence-PushNotificationConsoleAsset")
        .filter(PersistenceService.isPersistenceEventForEntityType(ConsoleAsset.class))
        .filter(isNotForGateway(gatewayService))
        .process(
            exchange -> {
              @SuppressWarnings("unchecked")
              PersistenceEvent<ConsoleAsset> persistenceEvent =
                  (PersistenceEvent<ConsoleAsset>) exchange.getIn().getBody(PersistenceEvent.class);
              final ConsoleAsset asset = persistenceEvent.getEntity();
              processConsoleAssetChange(asset, persistenceEvent);
            });
  }

  @Override
  public String getTypeName() {
    return PushNotificationMessage.TYPE;
  }

  @Override
  public boolean isMessageValid(AbstractNotificationMessage message) {

    if (!(message instanceof PushNotificationMessage pushMessage)) {
      LOG.warning(
          "Invalid message: '"
              + message.getClass().getSimpleName()
              + "' is not an instance of PushNotificationMessage");
      return false;
    }

    if (TextUtil.isNullOrEmpty(pushMessage.getTitle()) && pushMessage.getData() == null) {
      LOG.warning("Invalid message: must either contain a title and/or data");
      return false;
    }

    return true;
  }

  @Override
  public List<Notification.Target> getTargets(
      Notification.Source source,
      String sourceId,
      List<Notification.Target> targets,
      AbstractNotificationMessage message) {

    // Check if message is going to a topic if so then filter consoles subscribed to that topic
    PushNotificationMessage pushMessage = (PushNotificationMessage) message;
    List<Notification.Target> mappedTargets = new ArrayList<>();

    if (pushMessage.getTargetType() == TOPIC || pushMessage.getTargetType() == CONDITION) {
      mappedTargets.add(
          new Notification.Target(
              Notification.TargetType.CUSTOM,
              pushMessage.getTargetType() + ": " + pushMessage.getTarget()));
      return mappedTargets;
    }

    if (targets != null) {

      // Filter out console targets
      String[] assetTargets =
          targets.stream()
              .filter(target -> target.getType() == Notification.TargetType.ASSET)
              .map(Notification.Target::getId)
              .toArray(String[]::new);

      if (assetTargets.length > 0) {
        List<String> consoleAssets =
            assetStorageService
                .findAll(
                    new AssetQuery()
                        .ids(assetTargets)
                        .select(new AssetQuery.Select().excludeAttributes())
                        .types(ConsoleAsset.class))
                .stream()
                .map(Asset::getId)
                .toList();

        if (!consoleAssets.isEmpty()) {
          targets =
              targets.stream()
                  .filter(
                      target -> {
                        boolean isConsoleAsset = consoleAssets.contains(target.getId());
                        if (isConsoleAsset) {
                          // Don't filter out consoles without FCM here so we can record the failure
                          // in the actual send
                          mappedTargets.add(
                              new Notification.Target(
                                  Notification.TargetType.ASSET, target.getId()));
                        }
                        return !isConsoleAsset;
                      })
                  .collect(Collectors.toList());
        }
      }

      targets.forEach(
          target -> {
            Notification.TargetType targetType = target.getType();
            String targetId = target.getId();
            // Prefer consoles that still have an FCM token so a normal send is unchanged.
            // #3113: when none do, map FCM consoles anyway so the send records
            // "No FCM token found" instead of a missing-targets error. Browser consoles
            // (push version "web") stay out of both queries.
            List<String> consoleAssetIds =
                consoleIds(
                    consoleAssetsLinkedTo(targetType, targetId).attributes(nonEmptyFcmToken()));
            if (consoleAssetIds.isEmpty()) {
              consoleAssetIds =
                  consoleIds(
                      consoleAssetsLinkedTo(targetType, targetId).attributes(fcmProviderVersion()));
            }

            if (!consoleAssetIds.isEmpty()) {
              // Special handling if target type is user (don't need to find all linked users)
              if (targetType == Notification.TargetType.USER) {
                if (Arrays.stream(
                        managerIdentityService
                            .getIdentityProvider()
                            .queryUsers(
                                // Exclude service accounts, system accounts and accounts with
                                // disabled push notifications
                                new UserQuery()
                                    .ids(targetId)
                                    .serviceUsers(false)
                                    .attributes(
                                        new UserQuery.AttributeValuePredicate(
                                            true,
                                            new StringPredicate(User.SYSTEM_ACCOUNT_ATTRIBUTE)),
                                        new UserQuery.AttributeValuePredicate(
                                            true,
                                            new StringPredicate(
                                                PUSH_NOTIFICATIONS_DISABLED_ATTRIBUTE),
                                            new StringPredicate("true")))))
                    .allMatch(User::isSystemAccount)) {
                  consoleAssetIds = Collections.emptyList();
                }
              } else {

                consoleAssetIds =
                    consoleAssetIds.stream()
                        .filter(
                            consoleId -> {

                              //  Check there are no regular users with disabled push notifications
                              // linked to this console
                              // TODO: This should be handled by the console provider on the console
                              // itself
                              long count =
                                  Arrays.stream(
                                          managerIdentityService
                                              .getIdentityProvider()
                                              .queryUsers(
                                                  // Exclude service accounts, system accounts and
                                                  // accounts with disabled email notifications
                                                  new UserQuery()
                                                      .assets(consoleId)
                                                      .serviceUsers(false)
                                                      .attributes(
                                                          new UserQuery.AttributeValuePredicate(
                                                              true,
                                                              new StringPredicate(
                                                                  User.SYSTEM_ACCOUNT_ATTRIBUTE)),
                                                          new UserQuery.AttributeValuePredicate(
                                                              false,
                                                              new StringPredicate(
                                                                  PUSH_NOTIFICATIONS_DISABLED_ATTRIBUTE),
                                                              new StringPredicate("true")))))
                                      .count();
                              return count == 0;
                            })
                        .collect(Collectors.toList());
              }
            }

            if (consoleAssetIds.isEmpty()) {
              LOG.fine("No console asset targets have been mapped");
            } else {
              mappedTargets.addAll(
                  consoleAssetIds.stream()
                      .filter(id -> mappedTargets.stream().noneMatch(t -> t.getId().equals(id)))
                      .map(id -> new Notification.Target(Notification.TargetType.ASSET, id))
                      .toList());
            }
          });
    }

    if (devMode && mappedTargets.isEmpty() && targets != null && !targets.isEmpty()) {
      // Keep the no-targets behaviour identical to production but make the dropped notification
      // visible
      LOG.info(
          "Dev mode: no console asset targets mapped for push notification, original targets=["
              + targets.stream().map(Object::toString).collect(Collectors.joining(","))
              + "], message="
              + message);
    }

    return mappedTargets;
  }

  @Override
  public void sendMessage(
      long id,
      Notification.Source source,
      String sourceId,
      Notification.Target target,
      AbstractNotificationMessage message)
      throws Exception {

    Notification.TargetType targetType = target.getType();
    String targetId = target.getId();

    if (targetType != Notification.TargetType.ASSET
        && targetType != Notification.TargetType.CUSTOM) {
      String msg = "Target type not supported: " + targetType;
      LOG.warning(msg);
      throw new Exception(msg);
    }

    // A console without a token must fail even when FCM itself is not configured. Otherwise the
    // dev-mode skip below hides the missing token and the API reports success.
    String fcmToken = consoleFCMTokenMap.get(targetId);
    if (targetType == Notification.TargetType.ASSET && TextUtil.isNullOrEmpty(fcmToken)) {
      String msg = "No FCM token found for console: " + targetId;
      LOG.finer(msg);
      throw new Exception(msg);
    }

    if (devMode && !valid) {
      // FCM not configured; log the notification instead of sending so the sent record is still
      // created
      LOG.info(
          "Dev mode: FCM not configured so skipping push notification send: id="
              + id
              + ", target="
              + target
              + ", message="
              + message);
      return;
    }

    if (TextUtil.isNullOrEmpty(fcmToken)) {
      String msg = "No FCM token found for console: " + targetId;
      LOG.finer(msg);
      throw new Exception(msg);
    }

    PushNotificationMessage pushMessage = (PushNotificationMessage) message;

    // Assume DEVICE target if not specified
    if (pushMessage.getTargetType() == null) {
      pushMessage.setTargetType(DEVICE);
    }

    switch (pushMessage.getTargetType()) {
      case DEVICE ->
          // Always use fcm token from the console asset (so users cannot target other devices)
          pushMessage.setTarget(fcmToken);
      case TOPIC -> {}
      // TODO: Decide how to handle FCM topic support (too much power for users to put anything in
      // target)
      case CONDITION -> {}
        // TODO: Decide how to handle conditions support (too much power for users to put anything
        // in target)
    }

    sendMessage(buildFCMMessage(id, pushMessage));
  }

  //    public NotificationSendResult sendMessage(PushNotificationMessage.TargetType targetType,
  // String fcmTarget, Message.Builder messageBuilder) {
  //
  //        switch (targetType) {
  //            case DEVICE:
  //                messageBuilder.setToken(fcmTarget);
  //                break;
  //            case TOPIC:
  //                messageBuilder.setTopic(fcmTarget);
  //                break;
  //            case CONDITION:
  //                messageBuilder.setCondition(fcmTarget);
  //                break;
  //        }
  //
  //        NotificationSendResult result = sendMessage(messageBuilder.build());
  //        if (result.isSuccess()) {
  //            LOG.warning("FCM send to '" + targetType + ":" + fcmTarget + "' success");
  //        } else {
  //            LOG.warning("FCM send to '" + targetType + ":" + fcmTarget + "' failed: " +
  // result.getMessage());
  //        }
  //
  //        return result;
  //    }

  @Override
  public boolean isValid() {
    // In dev mode the handler is always valid so notifications are processed and persisted even
    // without FCM config
    return valid || devMode;
  }

  public void sendMessage(Message message) throws Exception {
    FirebaseMessaging.getInstance().send(message);
  }

  protected boolean isConsoleSubscribedToTopic(ConsoleAsset consoleAsset, String topic) {
    return consoleAsset
        .getConsoleProviders()
        .flatMap(
            consoleProviders ->
                Optional.ofNullable(consoleProviders.get(PushNotificationMessage.TYPE))
                    .map(ConsoleProvider::getData)
                    .map(data -> data.get("topics"))
                    .map(
                        arrayValue -> {
                          int length = Array.getLength(arrayValue);
                          for (int i = 0; i < length; i++) {
                            Object arrayElement = Array.get(arrayValue, i);
                            if (arrayElement instanceof String && arrayElement.equals(topic)) {
                              return true;
                            }
                          }
                          return false;
                        }))
        .orElse(false);
  }

  protected static Message buildFCMMessage(long id, PushNotificationMessage pushMessage) {

    Message.Builder builder = Message.builder();
    boolean dataOnly = TextUtil.isNullOrEmpty(pushMessage.getTitle());

    switch (pushMessage.getTargetType()) {
      case DEVICE -> builder.setToken(pushMessage.getTarget());
      case TOPIC -> builder.setTopic(pushMessage.getTarget());
      case CONDITION -> builder.setCondition(pushMessage.getTarget());
    }

    AndroidConfig.Builder androidConfigBuilder = AndroidConfig.builder();
    ApnsConfig.Builder apnsConfigBuilder = ApnsConfig.builder();
    Aps.Builder apsBuilder = Aps.builder();
    WebpushConfig.Builder webpushConfigBuilder = WebpushConfig.builder();

    if (!dataOnly) {
      // The OpenRemote console builds the Android notification from this data (rather than a basic
      // FCM
      // notification) so it can show a custom notification with actions even when the app is in the
      // background.
      // Always include the title/body: a plain push with no data/action/buttons would otherwise
      // arrive with
      // nothing for the Android console to display.
      androidConfigBuilder.putData("or-title", pushMessage.getTitle());
      if (pushMessage.getBody() != null) {
        androidConfigBuilder.putData("or-body", pushMessage.getBody());
      }

      // Use alert dictionary for apns
      // https://developer.apple.com/library/content/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/PayloadKeyReference.html
      apsBuilder
          .setAlert(
              ApsAlert.builder()
                  .setTitle(pushMessage.getTitle())
                  .setBody(pushMessage.getBody())
                  .build())
          .setSound("default")
          .setCategory("openremoteNotification");

      webpushConfigBuilder.setNotification(
          new WebpushNotification(pushMessage.getTitle(), pushMessage.getBody()));
    }

    if (pushMessage.getData() != null) {
      builder.putAllData(
          ValueUtil.JSON.convertValue(
              pushMessage.getData(), new TypeReference<Map<String, String>>() {}));

      if (dataOnly) {
        apsBuilder.setContentAvailable(true);
      }
    }

    // Store ID so console can mark notification as delivered and/or acknowledged
    builder.putData("notification-id", Long.toString(id));

    try {
      if (pushMessage.getAction() != null) {
        builder.putData("action", ValueUtil.asJSONOrThrow(pushMessage.getAction()));
      }

      if (pushMessage.getButtons() != null) {
        builder.putData("buttons", ValueUtil.asJSONOrThrow(pushMessage.getButtons()));
      }
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

    androidConfigBuilder.setPriority(
        pushMessage.getPriority() == PushNotificationMessage.MessagePriority.HIGH
            ? AndroidConfig.Priority.HIGH
            : AndroidConfig.Priority.NORMAL);
    apnsConfigBuilder.putHeader(
        "apns-priority", !dataOnly ? "10" : "5"); // Don't use 10 for data only

    // set the following APNS flag to allow console to customise the notification before delivery
    // and to ensure delivery
    apsBuilder.setMutableContent(true);

    if (pushMessage.getTtlSeconds() != null) {
      long timeToLiveSeconds = Math.max(pushMessage.getTtlSeconds(), 0);
      long timeToLiveMillis = timeToLiveSeconds * 1000;
      Date expirationDate = new Date(new Date().getTime() + timeToLiveMillis);
      long epochSeconds = Math.round(((float) expirationDate.getTime()) / 1000);

      apnsConfigBuilder.putHeader("apns-expiration", Long.toString(epochSeconds));
      androidConfigBuilder.setTtl(timeToLiveMillis);
      webpushConfigBuilder.putHeader("TTL", Long.toString(timeToLiveSeconds));
    }

    apnsConfigBuilder.setAps(apsBuilder.build());
    builder.setAndroidConfig(androidConfigBuilder.build());
    builder.setApnsConfig(apnsConfigBuilder.build());
    builder.setWebpushConfig(webpushConfigBuilder.build());

    return builder.build();
  }

  private AssetQuery consoleAssetsLinkedTo(Notification.TargetType targetType, String targetId) {
    AssetQuery assetQuery =
        new AssetQuery()
            .select(new AssetQuery.Select().excludeAttributes())
            .types(ConsoleAsset.class);

    switch (targetType) {
      case REALM ->
          // Any console assets in the target realm
          assetQuery.realm(new RealmPredicate(targetId));
      case USER ->
          // Any console assets linked to the target user
          assetQuery.userIds(targetId);
      case ASSET ->
          // Any users linked to this asset and then their linked console assets
          assetQuery.userIds(
              assetStorageService.findUserAssetLinks(null, null, targetId).stream()
                  .map(ual -> ual.getId().getUserId())
                  .collect(Collectors.toSet())
                  .toArray(String[]::new));
    }
    return assetQuery;
  }

  private static AttributePredicate nonEmptyFcmToken() {
    return new AttributePredicate(
        ConsoleAsset.CONSOLE_PROVIDERS,
        new ValueEmptyPredicate().negate(true),
        false,
        new NameValuePredicate.Path(PushNotificationMessage.TYPE, "data", "token"));
  }

  private static AttributePredicate fcmProviderVersion() {
    return new AttributePredicate(
        ConsoleAsset.CONSOLE_PROVIDERS,
        new StringPredicate(FCM_PROVIDER_NAME),
        false,
        new NameValuePredicate.Path(PushNotificationMessage.TYPE, "version"));
  }

  private List<String> consoleIds(AssetQuery assetQuery) {
    return assetStorageService.findAll(assetQuery).stream().map(Asset::getId).distinct().toList();
  }

  protected static boolean isLinkedToFcmProvider(ConsoleAsset asset) {
    return asset
        .getConsoleProviders()
        .flatMap(
            consoleProviders ->
                Optional.ofNullable(consoleProviders.get(PushNotificationMessage.TYPE))
                    .map(ConsoleProvider::getVersion)
                    .map(FCM_PROVIDER_NAME::equals))
        .orElse(false);
  }

  protected static Optional<String> getFcmToken(ConsoleAsset asset) {
    return asset
        .getConsoleProviders()
        .flatMap(
            consoleProviders ->
                Optional.ofNullable(consoleProviders.get(PushNotificationMessage.TYPE))
                    .map(ConsoleProvider::getData)
                    .map(
                        data -> {
                          Object token = data.get("token");
                          return token instanceof String ? (String) token : null;
                        }));
  }

  protected void processConsoleAssetChange(
      ConsoleAsset asset, PersistenceEvent<ConsoleAsset> persistenceEvent) {
    String fcmToken = consoleFCMTokenMap.remove(asset.getId());
    if (!TextUtil.isNullOrEmpty(fcmToken)) {
      fcmTokenBlacklist.remove(fcmToken);
    }

    switch (persistenceEvent.getCause()) {
      case CREATE, UPDATE ->
          getFcmToken(asset).ifPresent(token -> consoleFCMTokenMap.put(asset.getId(), token));
    }
  }
}
