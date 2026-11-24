/*
 * Copyright 2025, OpenRemote Inc.
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
package org.openremote.agent.protocol.lorawan;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import dev.failsafe.function.CheckedBiPredicate;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openremote.container.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.syslog.SyslogCategory;

public class GrpcRetryingRunner {

  public static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, GrpcRetryingRunner.class);

  private final String taskName;
  private final Supplier<String> clientUriSupplier;
  private volatile CompletableFuture<Void> future;
  private volatile ManagedChannel currentChannel;
  private volatile Future<?> scheduledContinuationFuture;

  public static boolean isNonRetryableCode(StatusRuntimeException exception) {
    if (exception != null) {
      Status.Code code = exception.getStatus().getCode();

      return code == Status.Code.NOT_FOUND
          || code == Status.Code.INVALID_ARGUMENT
          || code == Status.Code.UNAUTHENTICATED
          || code == Status.Code.FAILED_PRECONDITION
          || code == Status.Code.CANCELLED;
    }
    return false;
  }

  public GrpcRetryingRunner(String taskName, Supplier<String> clientUriSupplier) {
    this.taskName = taskName;
    this.clientUriSupplier = clientUriSupplier != null ? clientUriSupplier : () -> "";
  }

  public void start(
      Supplier<ManagedChannel> channelSupplier,
      ThrowingConsumer<ManagedChannel> task,
      boolean isVerboseLog,
      Duration initialBackoff,
      Duration maxBackoff) {
    start(channelSupplier, task, isVerboseLog, initialBackoff, maxBackoff, null);
  }

  public void start(
      Supplier<ManagedChannel> channelSupplier,
      ThrowingConsumer<ManagedChannel> task,
      boolean isVerboseLog,
      Duration initialBackoff,
      Duration maxBackoff,
      Duration scheduleInterval) {
    RunnableContinuation continuation;

    if (scheduleInterval != null) {
      continuation =
          () -> {
            scheduleNextStart(
                channelSupplier, task, isVerboseLog, initialBackoff, maxBackoff, scheduleInterval);
          };
    } else {
      continuation = () -> {};
    }

    CheckedBiPredicate<Object, Throwable> shouldNotRetry =
        (result, ex) -> {
          if (ex instanceof StatusRuntimeException sre) {
            return isNonRetryableCode(sre);
          }
          // Note: CancellationException is an IllegalStateException
          return ex instanceof IllegalStateException;
        };

    LOG.log(
        isVerboseLog ? Level.INFO : Level.FINE,
        "[" + taskName + "] Establishing gRPC connection: " + getClientUri());

    RetryPolicy<Object> retryPolicy =
        RetryPolicy.builder()
            .handle(Exception.class)
            .abortIf(shouldNotRetry)
            .withBackoff(initialBackoff, maxBackoff)
            .withJitter(initialBackoff.minusMillis(1))
            .onRetryScheduled(
                e ->
                    LOG.log(
                        isVerboseLog ? Level.INFO : Level.FINE,
                        () ->
                            "["
                                + taskName
                                + "] "
                                + "Re-connection scheduled in '"
                                + e.getDelay()
                                + "' for: "
                                + getClientUri()))
            .onFailedAttempt(
                e ->
                    LOG.log(
                        isVerboseLog ? Level.INFO : Level.FINE,
                        () ->
                            "["
                                + taskName
                                + "] "
                                + "Connection attempt failed '"
                                + e.getAttemptCount()
                                + "' for: "
                                + getClientUri()
                                + ", error="
                                + (e.getLastException() != null
                                    ? e.getLastException().getMessage()
                                    : null)))
            .onSuccess(
                e ->
                    LOG.fine(
                        "[" + taskName + "] gRPC connection attempt success: " + getClientUri()))
            .withMaxRetries(Integer.MAX_VALUE)
            .build();

    future =
        Failsafe.with(retryPolicy)
            .with(Container.EXECUTOR)
            .runAsyncExecution(
                execution -> {
                  LOG.fine(
                      () ->
                          "["
                              + taskName
                              + "] Connection attempt '"
                              + (execution.getAttemptCount() + 1)
                              + "' for: "
                              + getClientUri());
                  ManagedChannel channel = channelSupplier.get();
                  currentChannel = channel;
                  try {
                    task.accept(channel);
                    execution.recordResult(null);
                  } catch (InterruptedException ex) {
                    throw new CancellationException("[" + taskName + "] canceled");
                  } finally {
                    shutdownChannel(channel);
                    currentChannel = null;
                  }
                });
    future.whenComplete(
        (result, ex) -> {
          if (ex instanceof CancellationException) {
            LOG.info("[" + taskName + "] gRPC connection shutdown for: " + getClientUri());
          } else if (ex != null) {
            LOG.warning(
                "["
                    + taskName
                    + "] gRPC connection unexpectedly stopped for: "
                    + getClientUri()
                    + ", error="
                    + ex.getMessage());
          } else {
            try {
              continuation.run();
            } catch (Exception e) {
              LOG.warning(
                  "["
                      + taskName
                      + "] Error during continuation for: "
                      + getClientUri()
                      + ", error="
                      + e.getMessage());
            }
          }
        });
  }

  public void startStream(
      Supplier<ManagedChannel> channelSupplier,
      ThrowingConsumer<ManagedChannel> task,
      Duration initialBackoff,
      Duration maxBackoff,
      ConnectionStatusObserver observer) {

    Runnable notifyConnecting =
        () -> {
          if (observer != null) observer.onStatusChange(ConnectionStatus.CONNECTING);
        };
    Runnable notifyError =
        () -> {
          if (observer != null) observer.onStatusChange(ConnectionStatus.ERROR);
        };

    CheckedBiPredicate<Object, Throwable> permanentAbort =
        (result, ex) -> {
          if (ex instanceof StatusRuntimeException sre) {
            return isNonRetryableCode(sre);
          }
          // Note: CancellationException is an IllegalStateException
          return ex instanceof IllegalStateException;
        };

    CheckedBiPredicate<Object, Throwable> innerAbort =
        (result, ex) -> ex instanceof StreamBreakSignalException || permanentAbort.test(result, ex);

    RetryPolicy<Object> innerPolicy =
        RetryPolicy.builder()
            .handle(Exception.class)
            .abortIf(innerAbort)
            .withBackoff(initialBackoff, maxBackoff)
            .withJitter(initialBackoff.minusMillis(1))
            .onRetryScheduled(
                e ->
                    LOG.info(
                        "["
                            + taskName
                            + "] "
                            + "Re-connection scheduled in '"
                            + e.getDelay()
                            + "' for: "
                            + getClientUri()))
            .onFailedAttempt(
                e -> {
                  Throwable ex = e.getLastException();
                  if (ex instanceof StreamBreakSignalException streamException) {
                    Throwable cause = streamException.getCause();
                    LOG.info(
                        "["
                            + taskName
                            + "] "
                            + "gRPC stream failure for: "
                            + getClientUri()
                            + ", error="
                            + (cause != null ? cause.getMessage() : streamException.getMessage()));
                  } else if (!isCancelledException(e.getLastException())) {
                    LOG.info(
                        "["
                            + taskName
                            + "] "
                            + "Connection attempt failed '"
                            + e.getAttemptCount()
                            + "' for: "
                            + getClientUri()
                            + ", error="
                            + (e.getLastException() != null
                                ? e.getLastException().getMessage()
                                : null));
                  }
                })
            .withMaxRetries(Integer.MAX_VALUE)
            .build();

    RetryPolicy<Object> outerPolicy =
        RetryPolicy.builder()
            .handle(Exception.class)
            .abortIf(permanentAbort)
            .withMaxRetries(Integer.MAX_VALUE)
            .withDelay(Duration.ofSeconds(1))
            .build();

    future =
        Failsafe.with(outerPolicy)
            .with(Container.EXECUTOR)
            .runAsync(
                () -> {
                  Failsafe.with(innerPolicy)
                      .with(Container.EXECUTOR)
                      .run(
                          () -> {
                            ManagedChannel channel = channelSupplier.get();
                            currentChannel = channel;
                            try {
                              notifyConnecting.run();
                              LOG.info(
                                  "["
                                      + taskName
                                      + "] Establishing gRPC stream connection for: "
                                      + getClientUri());
                              task.accept(channel);
                            } catch (InterruptedException exception) {
                              throw new CancellationException(
                                  "[" + taskName + "] canceled for: " + getClientUri());
                            } finally {
                              shutdownChannel(channel);
                              currentChannel = null;
                            }
                          });
                });

    future.whenComplete(
        (result, ex) -> {
          if (ex instanceof CancellationException) {
            LOG.info("[" + taskName + "] gRPC stream connection shutdown for: " + getClientUri());
          } else if (ex != null) {
            LOG.warning(
                "["
                    + taskName
                    + "] gRPC stream connection unexpectedly stopped for: "
                    + getClientUri()
                    + ", error="
                    + ex.getMessage());
            notifyError.run();
          }
        });
  }

  public void shutdown() {
    if (future != null && !future.isDone()) {
      future.cancel(true);
    }

    if (scheduledContinuationFuture != null) {
      scheduledContinuationFuture.cancel(false);
      scheduledContinuationFuture = null;
    }

    if (currentChannel != null) {
      shutdownChannel(currentChannel);
    }
  }

  private void shutdownChannel(ManagedChannel channel) {
    if (channel == null) {
      return;
    }
    try {
      channel.shutdown();
      if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      channel.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private String getClientUri() {
    return clientUriSupplier.get();
  }

  private void scheduleNextStart(
      Supplier<ManagedChannel> channelSupplier,
      ThrowingConsumer<ManagedChannel> task,
      boolean isVerboseLog,
      Duration initialBackoff,
      Duration maxBackoff,
      Duration scheduleInterval) {

    if (scheduledContinuationFuture != null) {
      scheduledContinuationFuture.cancel(false);
    }

    Runnable delayedTask =
        () -> {
          this.start(
              channelSupplier, task, isVerboseLog, initialBackoff, maxBackoff, scheduleInterval);
          this.scheduledContinuationFuture = null;
        };

    this.scheduledContinuationFuture =
        Container.SCHEDULED_EXECUTOR.schedule(
            delayedTask, scheduleInterval.toMillis(), TimeUnit.MILLISECONDS);
    LOG.info(
        () ->
            "["
                + taskName
                + "] Scheduled next execution in '"
                + scheduleInterval
                + "' for: "
                + getClientUri());
  }

  private boolean isCancelledException(Throwable exception) {
    return (exception instanceof CancellationException)
        || ((exception instanceof StatusRuntimeException)
            && (((StatusRuntimeException) exception).getStatus().getCode()
                == Status.Code.CANCELLED));
  }

  @FunctionalInterface
  public interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
  }

  @FunctionalInterface
  public interface RunnableContinuation {
    void run();
  }

  @FunctionalInterface
  public interface ConnectionStatusObserver {
    void onStatusChange(ConnectionStatus status);
  }

  public static class StreamBreakSignalException extends IOException {
    public StreamBreakSignalException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
