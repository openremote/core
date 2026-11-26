/*
 * Copyright 2023, OpenRemote Inc.
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
package org.openremote.agent.protocol.mail;

import io.undertow.util.Headers;
import jakarta.mail.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.openremote.container.util.MailUtil;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.mail.MailMessage;
import org.openremote.model.syslog.SyslogCategory;

public class MailClient {

  public static final System.Logger LOG =
      System.getLogger(MailClient.class.getName() + "." + SyslogCategory.AGENT.name());
  protected MailClientBuilder config;
  protected Session session;
  protected Date lastMessageDate;
  protected Future<?> mailChecker;
  protected boolean persistenceFileAccessible;
  protected final AtomicBoolean connected = new AtomicBoolean(false);
  protected List<Consumer<ConnectionStatus>> connectionListeners = new CopyOnWriteArrayList<>();
  protected List<Consumer<MailMessage>> messageListeners = new CopyOnWriteArrayList<>();

  MailClient(MailClientBuilder config) {
    this.config = config;

    if (config.getPersistenceDir() != null) {
      if (!Files.exists(config.getPersistenceDir())) {
        LOG.log(
            System.Logger.Level.INFO,
            "Persistence directory doesn't exist, attempting to create it: "
                + config.getPersistenceDir());
        try {
          Files.createDirectories(config.getPersistenceDir());
        } catch (IOException e) {
          LOG.log(System.Logger.Level.INFO, "Persistence directory creation failed", e);
        }
      } else if (!Files.isDirectory(config.getPersistenceDir())) {
        LOG.log(
            System.Logger.Level.INFO,
            "Persistence directory is not a directory: " + config.getPersistenceDir());
      } else {
        persistenceFileAccessible = true;
        lastMessageDate = readLastMessageDate(config);
      }
    }

    if (config.getEarliestMessageDate() != null
        && (lastMessageDate == null || lastMessageDate.before(config.getEarliestMessageDate()))) {
      lastMessageDate = config.getEarliestMessageDate();
    }

    this.session = Session.getInstance(config.getProperties());
  }

  public boolean connect() {
    synchronized (connected) {
      if (connected.get()) {
        return true;
      }
      return doConnect();
    }
  }

  protected boolean doConnect() {

    // Try and connect to the mailbox folder
    try {
      withFolder(
          (folder) -> {
            connected.set(true);
            mailChecker =
                config
                    .getScheduledExecutorService()
                    .scheduleWithFixedDelay(
                        this::checkForMessages,
                        config.getCheckInitialDelaySeconds(),
                        config.getCheckIntervalSeconds(),
                        TimeUnit.SECONDS);
          });

      updateConnectionStatus(ConnectionStatus.CONNECTED);
      return true;
    } catch (Exception e) {
      updateConnectionStatus(ConnectionStatus.ERROR);
      return false;
    }

    // For IMAP folders we can use listener for new messages for POP3 we need to open/close the
    // folder
    // we'll just use a mechanism that should work for both for now
    //            emailFolder.addMessageCountListener(new MessageCountAdapter() {
    //                public void messagesAdded(MessageCountEvent ev) {
    //                    Message[] messages = ev.getMessages();
    //                    LOG.log(System.Logger.Level.TRACE, "Got " + messages.length + "
    // messages");
    //
    //                    if (!initialized) {
    //                        initialized = true;
    //                        // Filter out earlier messages on first pass
    //                        Arrays.stream(messages)
    //                            .filter(message -> {
    //                                try {
    //                                    if (lastMessageDate != null) {
    //                                        if (message.getReceivedDate().before(lastMessageDate))
    // {
    //                                            return false;
    //                                        }
    //                                    }
    //                                } catch (MessagingException e) {
    //                                    LOG.log(System.Logger.Level.TRACE, "Failed to read message
    // received date", e);
    //                                    return false;
    //                                }
    //                                return true;
    //                            })
    //                            .forEach(message -> onMessage(message));
    //                    } else {
    //                        Arrays.stream(messages).forEach(message -> onMessage(message));
    //                    }
    //                }
    //            });
  }

  protected void withFolder(Consumer<Folder> folderConsumer) throws Exception {
    try {
      LOG.log(System.Logger.Level.INFO, "Connecting to mail server: " + config.getHost());

      try (Store mailStore = session.getStore()) {
        UsernamePassword usernamePassword = config.getAuth();
        mailStore.connect(usernamePassword.getUsername(), usernamePassword.getPassword());
        Folder mailFolder = mailStore.getFolder(config.getFolder());

        try {
          if (mailFolder == null || !mailFolder.exists()) {
            LOG.log(
                System.Logger.Level.WARNING, "Mailbox folder doesn't exist: " + config.getFolder());
            throw new Exception();
          }

          mailFolder.open(
              config.isDeleteMessageOnceProcessed() ? Folder.READ_WRITE : Folder.READ_ONLY);
          folderConsumer.accept(mailFolder);
        } finally {
          if (mailFolder != null) {
            if (mailFolder.isOpen()) {
              if (config.isDeleteMessageOnceProcessed()) {
                mailFolder.close(true);
              } else {
                mailFolder.close();
              }
            }
          }
        }
      }
    } catch (Exception e) {
      if (e instanceof AuthenticationFailedException) {
        LOG.log(System.Logger.Level.ERROR, "Failed to connect to mailbox due to auth issue", e);
      } else if (e instanceof MessagingException) {
        LOG.log(System.Logger.Level.ERROR, "Failed to connect to mailbox", e);
      }
      throw e;
    }
  }

  public void disconnect() {
    synchronized (connected) {
      if (!connected.get()) {
        return;
      }

      LOG.log(System.Logger.Level.INFO, "Disconnecting server: " + config.getHost());
      mailChecker.cancel(true);
      mailChecker = null;
      connected.set(false);
      updateConnectionStatus(ConnectionStatus.DISCONNECTED);
    }
  }

  public boolean isConnected() {
    return connected.get();
  }

  public void addConnectionListener(Consumer<ConnectionStatus> listener) {
    connectionListeners.add(listener);
  }

  public void removeConnectionListener(Consumer<ConnectionStatus> listener) {
    connectionListeners.remove(listener);
  }

  public void addMessageListener(Consumer<MailMessage> listener) {
    messageListeners.add(listener);
  }

  public void removeMessageListener(Consumer<MailMessage> listener) {
    messageListeners.remove(listener);
  }

  protected void updateConnectionStatus(ConnectionStatus connectionStatus) {
    connectionListeners.forEach(listener -> listener.accept(connectionStatus));
  }

  protected void onMessage(MailMessage message) {
    try {
      messageListeners.forEach(listener -> listener.accept(message));
    } catch (Exception e) {
      LOG.log(System.Logger.Level.ERROR, "Failed to process received message", e);
    }
  }

  protected void checkForMessages() {

    try {
      withFolder(
          (emailFolder) -> {
            updateConnectionStatus(ConnectionStatus.CONNECTED);
            try {
              Message[] messages = emailFolder.getMessages();

              // Fetch profile only works for IMAP - For POP3 the headers will be loaded and cached
              // once we read one of them
              FetchProfile fp = new FetchProfile();
              fp.add(FetchProfile.Item.ENVELOPE);
              fp.add(Headers.CONTENT_TYPE_STRING);
              emailFolder.fetch(messages, fp);
              Date lastRunLastMessageDate = lastMessageDate;

              Arrays.stream(messages)
                  .filter(
                      message -> {
                        try {
                          if (message.getFlags().contains(Flags.Flag.SEEN)) {
                            LOG.log(
                                System.Logger.Level.TRACE,
                                "Message has already been seen so ignoring: num="
                                    + message.getMessageNumber());
                            return false;
                          }
                          if (lastRunLastMessageDate != null) {
                            Date sentDate = message.getSentDate();

                            if (sentDate.before(lastRunLastMessageDate)
                                || sentDate.equals(lastRunLastMessageDate)) {
                              LOG.log(
                                  System.Logger.Level.TRACE,
                                  "Message is older than last message date so ignoring: "
                                      + messageToString(message));
                              return false;
                            }
                          }
                        } catch (MessagingException e) {
                          LOG.log(
                              System.Logger.Level.TRACE,
                              "Failed to read message details: num=" + message.getMessageNumber());
                          return false;
                        }

                        return true;
                      })
                  .forEach(
                      message -> {
                        try {
                          MailMessage mailMessage =
                              MailUtil.toMailMessage(message, config.isPreferHTML());

                          if (mailMessage == null) {
                            LOG.log(
                                System.Logger.Level.INFO,
                                "Unsupported mail message only messages with a text/* part are supported:"
                                    + messageToString(message));
                          } else {
                            onMessage(mailMessage);

                            if (config.isDeleteMessageOnceProcessed()) {
                              message.setFlag(Flags.Flag.DELETED, true);
                            }
                            if (lastMessageDate == null
                                || message.getSentDate().after(lastMessageDate)) {
                              lastMessageDate = message.getSentDate();
                            }
                          }
                        } catch (IOException e) {
                          LOG.log(
                              System.Logger.Level.INFO,
                              "Failed to read message content: " + messageToString(message),
                              e);
                        } catch (MessagingException e) {
                          LOG.log(
                              System.Logger.Level.INFO,
                              "An exception occurred while processing a message: "
                                  + messageToString(message),
                              e);
                        }
                      });

              if (persistenceFileAccessible
                  && lastMessageDate != null
                  && (lastRunLastMessageDate == null
                      || lastMessageDate.after(lastRunLastMessageDate))) {
                writeLastMessageDate(config, lastMessageDate);
              }
            } catch (MessagingException e) {
              LOG.log(
                  System.Logger.Level.WARNING, "An error occurred while trying to read mail", e);
            }
          });
      updateConnectionStatus(ConnectionStatus.WAITING);
    } catch (Exception e) {
      LOG.log(System.Logger.Level.WARNING, "An error occurred while processing mail messages", e);
      updateConnectionStatus(ConnectionStatus.ERROR);
    }
  }

  public MailClientBuilder getConfig() {
    return config;
  }

  protected static Date readLastMessageDate(MailClientBuilder config) {
    Path filePath = getLastMessageFilePath(config);
    LOG.log(
        System.Logger.Level.INFO,
        "Trying to read last message date from: " + filePath.toAbsolutePath());

    if (!Files.exists(filePath)) {
      LOG.log(System.Logger.Level.INFO, "Last message date file does not currently exist");
      return null;
    }

    try {
      String millisStr = Files.readString(filePath);
      return new Date(Long.parseLong(millisStr));
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to read last message date from: " + filePath, e);
    }

    return null;
  }

  protected static boolean writeLastMessageDate(MailClientBuilder config, Date lastMessageDate) {
    Path filePath = getLastMessageFilePath(config);
    LOG.log(
        System.Logger.Level.INFO,
        "Trying to write last message date to: " + filePath.toAbsolutePath());
    try {
      Files.writeString(
          filePath,
          Long.toString(lastMessageDate.getTime()),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE);
      return true;
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to write last message date to: " + filePath, e);
    }
    return false;
  }

  protected static final Path getLastMessageFilePath(MailClientBuilder config) {
    String fileName = config.getHost() + "." + config.getPort() + "." + config.getUser();
    return config.getPersistenceDir().resolve(fileName);
  }

  public static final String messageToString(Message message) {
    String subject = null;
    Date sentDate = null;

    try {
      subject = message.getSubject();
      sentDate = message.getSentDate();
    } catch (MessagingException e) {
      LOG.log(
          System.Logger.Level.TRACE, "Message to string failed to extract basic message headers");
    }

    return "Message{subject=" + subject + ", sentDate=" + sentDate + "}";
  }
}
