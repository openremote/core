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
package org.openremote.agent.protocol.io;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openremote.container.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.syslog.SyslogCategory;

/** Abstract implementation of {@link IOServer} that uses the Netty library. */
@SuppressWarnings("unchecked")
public abstract class AbstractNettyIOServer<
        T, U extends Channel, V extends AbstractBootstrap<?, ?>, W extends SocketAddress>
    implements IOServer<T, U, W> {

  protected static final int INITIAL_RECONNECT_DELAY_MILLIS = 1000;
  protected static final int MAX_RECONNECT_DELAY_MILLIS = 60000;
  protected static final int RECONNECT_BACKOFF_MULTIPLIER = 2;
  protected static final Logger LOG =
      SyslogCategory.getLogger(PROTOCOL, AbstractNettyIOServer.class);
  protected final ExecutorService executorService;
  protected final ScheduledExecutorService scheduledExecutorService;
  protected int clientLimit = 0; // 0 means no limit
  protected V bootstrap;
  protected ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
  protected ChannelFuture channelFuture;
  protected EventLoopGroup workerGroup;
  protected U channel;
  protected final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  protected final List<IoServerMessageConsumer<T, U, W>> messageConsumers = new ArrayList<>();
  protected final List<Consumer<ConnectionStatus>> connectionStatusConsumers = new ArrayList<>();
  protected final List<BiConsumer<U, ConnectionStatus>> clientConnectionStatusConsumers =
      new ArrayList<>();
  protected ScheduledFuture<?> reconnectTask;
  protected int reconnectDelayMilliseconds = INITIAL_RECONNECT_DELAY_MILLIS;

  public AbstractNettyIOServer() {
    this.executorService = Container.EXECUTOR;
    this.scheduledExecutorService = Container.SCHEDULED_EXECUTOR;
  }

  @Override
  public synchronized void start() {
    if (connectionStatus != ConnectionStatus.DISCONNECTED
        && connectionStatus != ConnectionStatus.WAITING) {
      LOG.finest("Must be disconnected before calling start: " + getSocketAddressString());
      return;
    }

    LOG.fine("Starting IO Server: " + getSocketAddressString());
    onConnectionStatusChanged(ConnectionStatus.CONNECTING);

    if (workerGroup == null) {
      // TODO: In Netty 5 you can pass in an executor service; can only pass in thread factory for
      // now
      workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    try {
      bootstrap = createAndConfigureBootstrap();

      bootstrap.handler(
          new ChannelInitializer<U>() {
            @Override
            public void initChannel(U channel) {
              AbstractNettyIOServer.this.initChannel(channel);
            }
          });

      // Bind and start to accept incoming connections.
      channelFuture = bootstrap.bind().sync();
      channel = (U) channelFuture.channel();
      // allChannels.add(channelFuture.channel());

      // Add channel callback - this gets called when the channel connects or when channel
      // encounters an error
      channelFuture.addListener(
          new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
              synchronized (AbstractNettyIOServer.this) {
                channelFuture.removeListener(this);

                if (connectionStatus == ConnectionStatus.DISCONNECTING) {
                  return;
                }

                if (future.isSuccess()) {
                  LOG.log(Level.INFO, "Connection initializing: " + getSocketAddressString());
                  reconnectTask = null;
                  reconnectDelayMilliseconds = INITIAL_RECONNECT_DELAY_MILLIS;
                } else if (future.cause() != null) {
                  LOG.log(
                      Level.WARNING,
                      "Connection error: " + getSocketAddressString(),
                      future.cause());
                  // Failed to connect so schedule reconnection attempt
                  scheduleReconnect();
                }
              }
            }
          });

      // Add closed callback
      channel
          .closeFuture()
          .addListener(
              future -> {
                if (connectionStatus != ConnectionStatus.DISCONNECTING) {
                  scheduleReconnect();
                }
              });

    } catch (Exception e) {
      LOG.log(Level.SEVERE, "An error occurred while starting the server so shutting down", e);
      stop();
    }
  }

  @Override
  public synchronized void stop() {
    if (connectionStatus == ConnectionStatus.DISCONNECTING
        || connectionStatus == ConnectionStatus.DISCONNECTED) {
      LOG.finest("Already stopping or stopped: " + getSocketAddressString());
      return;
    }

    LOG.fine("Stopping IO Server: " + getSocketAddressString());
    onConnectionStatusChanged(ConnectionStatus.DISCONNECTING);

    try {
      if (reconnectTask != null) {
        reconnectTask.cancel(false);
      }

      // Close all client connections
      allChannels.close().sync();
      allChannels.clear();

      if (channelFuture != null) {
        channelFuture.cancel(true);
        channelFuture.sync();
        channelFuture = null;
      }

      // Close the channel
      if (channel != null) {
        channel.close().sync();
        channel = null;
      }

    } catch (InterruptedException ignored) {

    } finally {
      if (workerGroup != null) {
        workerGroup.shutdownGracefully();
        workerGroup = null;
      }
      onConnectionStatusChanged(ConnectionStatus.DISCONNECTED);
    }
  }

  @Override
  public void addMessageConsumer(IoServerMessageConsumer<T, U, W> messageConsumer) {
    LOG.finest("Adding message consumer");
    synchronized (messageConsumers) {
      messageConsumers.add(messageConsumer);
    }
  }

  @Override
  public void removeMessageConsumer(IoServerMessageConsumer<T, U, W> messageConsumer) {
    synchronized (messageConsumers) {
      messageConsumers.remove(messageConsumer);
    }
  }

  @Override
  public synchronized void removeAllMessageConsumers() {
    synchronized (messageConsumers) {
      messageConsumers.clear();
    }
  }

  @Override
  public void addConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
    synchronized (clientConnectionStatusConsumers) {
      connectionStatusConsumers.add(connectionStatusConsumer);
    }
  }

  @Override
  public void removeConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
    synchronized (clientConnectionStatusConsumers) {
      connectionStatusConsumers.remove(connectionStatusConsumer);
    }
  }

  @Override
  public void addConnectionStatusConsumer(
      BiConsumer<U, ConnectionStatus> connectionStatusConsumer) {
    synchronized (clientConnectionStatusConsumers) {
      clientConnectionStatusConsumers.add(connectionStatusConsumer);
    }
  }

  @Override
  public void removeConnectionStatusConsumer(
      BiConsumer<U, ConnectionStatus> connectionStatusConsumer) {
    synchronized (clientConnectionStatusConsumers) {
      clientConnectionStatusConsumers.remove(connectionStatusConsumer);
    }
  }

  @Override
  public void removeAllConnectionStatusConsumers() {
    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.clear();
    }
  }

  @Override
  public ConnectionStatus getConnectionStatus() {
    return connectionStatus;
  }

  @Override
  public ConnectionStatus getConnectionStatus(U client) {
    return client.isActive() ? ConnectionStatus.CONNECTED : ConnectionStatus.DISCONNECTED;
  }

  @Override
  public void disconnectClient(U client) {
    LOG.finest("Disconnecting client: " + getClientDescriptor(client));
    client.close();
  }

  protected void initChannel(U channel) {
    channel
        .pipeline()
        .addLast(
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelActive(ChannelHandlerContext ctx) throws Exception {
                synchronized (AbstractNettyIOServer.this) {
                  LOG.fine("Connected: " + getSocketAddressString());
                  onConnectionStatusChanged(ConnectionStatus.CONNECTED);
                }
                super.channelActive(ctx);
              }

              @Override
              public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                synchronized (AbstractNettyIOServer.this) {
                  if (connectionStatus != ConnectionStatus.DISCONNECTING) {
                    // This is a connection failure so ignore as reconnect logic will handle it
                    return;
                  }

                  LOG.fine("Disconnected: " + getSocketAddressString());
                  onConnectionStatusChanged(ConnectionStatus.DISCONNECTED);
                }
                super.channelInactive(ctx);
              }
            });
  }

  /**
   * Initialize the specified client channel (will be called when a new client connection is made)
   */
  protected void initClientChannel(U channel) {
    LOG.fine("Client initializing: " + getClientDescriptor(channel));

    // Add handler to track when a channel becomes active and to handle exceptions
    channel
        .pipeline()
        .addLast(
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelActive(ChannelHandlerContext ctx) throws Exception {
                onClientConnected(channel);
                super.channelActive(ctx);
              }

              @Override
              public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                onClientDisconnected(channel);
                super.channelInactive(ctx);
              }

              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                  throws Exception {
                onDecodeException(ctx, cause);
                super.exceptionCaught(ctx, cause);
              }
            });

    channel
        .pipeline()
        .addLast(
            new ChannelOutboundHandlerAdapter() {
              @SuppressWarnings("deprecation")
              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                  throws Exception {
                onEncodeException(ctx, cause);
                super.exceptionCaught(ctx, cause);
              }
            });

    addDecoders(channel);
    addEncoders(channel);

    // Add handler to route the final messages
    channel
        .pipeline()
        .addLast(
            new SimpleChannelInboundHandler<T>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx, T msg) {
                handleMessageReceived(channel, msg);
              }
            });
  }

  @SuppressWarnings("unchecked")
  protected void handleMessageReceived(U channel, T message) {
    onMessageReceived(message, channel, (W) channel.remoteAddress());
  }

  protected void onClientDisconnected(U client) {
    LOG.fine("Client disconnected: " + getClientDescriptor(client));
    // Channel group manages removal of stale channels but while we're here
    allChannels.remove(client);
    sendClientConnectionStatus(client, ConnectionStatus.DISCONNECTED);
  }

  protected void onClientConnected(U client) {
    LOG.fine("Client connected: " + getClientDescriptor(client));
    allChannels.add(client);
    sendClientConnectionStatus(client, ConnectionStatus.CONNECTED);
  }

  protected synchronized void onConnectionStatusChanged(ConnectionStatus connectionStatus) {
    this.connectionStatus = connectionStatus;

    synchronized (connectionStatusConsumers) {
      connectionStatusConsumers.forEach(consumer -> consumer.accept(connectionStatus));
    }
  }

  protected void sendClientConnectionStatus(U channel, ConnectionStatus connectionStatus) {
    synchronized (clientConnectionStatusConsumers) {
      clientConnectionStatusConsumers.forEach(
          statusConsumer -> statusConsumer.accept(channel, connectionStatus));
    }
  }

  protected void onMessageReceived(T message, U channel, W sender) {
    synchronized (messageConsumers) {
      messageConsumers.forEach(messageConsumer -> messageConsumer.accept(message, channel, sender));
    }
  }

  protected void onDecodeException(ChannelHandlerContext ctx, Throwable cause) {
    LOG.log(Level.SEVERE, "Exception occurred on in-bound message: ", cause);
    // Forcibly close the client connection
    ctx.channel().close();
  }

  protected void onEncodeException(ChannelHandlerContext ctx, Throwable cause) {
    LOG.log(Level.SEVERE, "Exception occurred on out-bound message: ", cause);
    ctx.channel().close();
  }

  @Override
  public void sendMessage(T message, U client) {
    try {
      client.writeAndFlush(message);
      LOG.finest("Message sent to client: " + getClientDescriptor(client));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Message send failed", e);
    }
  }

  @Override
  public void sendMessage(T message) {
    try {
      allChannels.writeAndFlush(message);
      LOG.finest("Message sent to all clients");
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Message send failed", e);
    }
  }

  /**
   * Send a message to a recipient by address; for connection based protocols then the recipient
   * must already be connected; this is mostly useful for connectionless protocols (i.e. UDP where
   * only a single channel exists)
   */
  public void sendMessage(T message, W recipient) {
    U client =
        (U)
            allChannels.stream()
                .filter(c -> Objects.equals(c.remoteAddress(), recipient))
                .findFirst()
                .orElse(null);

    if (client == null) {
      LOG.warning(
          "Couldn't find existing connection for recipient '"
              + recipient.toString()
              + "': "
              + getSocketAddressString());
      return;
    }

    sendMessage(message, client);
  }

  protected synchronized void scheduleReconnect() {
    if (reconnectTask != null) {
      return;
    }

    onConnectionStatusChanged(ConnectionStatus.WAITING);

    if (reconnectDelayMilliseconds < MAX_RECONNECT_DELAY_MILLIS) {
      reconnectDelayMilliseconds *= RECONNECT_BACKOFF_MULTIPLIER;
      reconnectDelayMilliseconds = Math.min(MAX_RECONNECT_DELAY_MILLIS, reconnectDelayMilliseconds);
    }

    LOG.finest(
        "Scheduling reconnection in '"
            + reconnectDelayMilliseconds
            + "' milliseconds: "
            + getSocketAddressString());

    reconnectTask =
        scheduledExecutorService.schedule(
            () -> {
              synchronized (AbstractNettyIOServer.this) {
                reconnectTask = null;

                // Attempt to reconnect if not disconnecting
                if (connectionStatus != ConnectionStatus.DISCONNECTING
                    && connectionStatus != ConnectionStatus.DISCONNECTED) {
                  start();
                }
              }
            },
            reconnectDelayMilliseconds,
            TimeUnit.MILLISECONDS);
  }

  /**
   * Get a string identifier that uniquely identifies the current instance of this server. e.g.
   * tcp://IP:PORT
   */
  protected abstract String getSocketAddressString();

  /** Create and configure the bootstrap to use for this instance */
  protected abstract V createAndConfigureBootstrap();

  /** Should return a descriptor to identify the specified client for use in log files etc. */
  protected abstract String getClientDescriptor(U client);

  protected abstract void addDecoders(U channel);

  protected abstract void addEncoders(U channel);

  protected void addDecoder(U channel, ChannelInboundHandler decoder) {
    channel.pipeline().addLast(decoder);
  }

  protected void addEncoder(U channel, ChannelOutboundHandler encoder) {
    channel.pipeline().addLast(encoder);
  }
}
