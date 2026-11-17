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
package org.openremote.manager.notification;

import org.openremote.model.notification.AbstractNotificationMessage;
import org.openremote.model.notification.Notification;

/** The reason why sending an {@link org.openremote.model.notification.Notification} failed. */
public class NotificationProcessingException extends RuntimeException {

  public enum Reason {

    /** Missing {@link Notification.Source}. */
    MISSING_SOURCE,

    /** Missing {@link Notification}. */
    MISSING_NOTIFICATION,

    /** Missing {@link AbstractNotificationMessage}. */
    MISSING_CONTENT,

    /** Missing {@link Notification.Target}. */
    MISSING_TARGETS,

    /** The {@link AbstractNotificationMessage} type is not supported. */
    UNSUPPORTED_MESSAGE_TYPE,

    /**
     * The actioner doesn't have sufficient access to send notifications to one or more specified
     * targets.
     */
    INSUFFICIENT_ACCESS,

    /**
     * The message is deemed to be invalid as determined by {@link
     * NotificationHandler#isMessageValid}.
     */
    INVALID_MESSAGE,

    /** An error occurred while trying to map target IDs to actual targets. */
    ERROR_TARGET_MAPPING,

    /** {@link NotificationHandler} failed to return any results. */
    SEND_FAILURE,

    /**
     * {@link NotificationHandler#isValid} returned false indicating a configuration error with the
     * handler.
     */
    NOTIFICATION_HANDLER_CONFIG_ERROR
  }

  protected final Reason reason;

  public NotificationProcessingException(Reason reason) {
    this(reason, null);
  }

  public NotificationProcessingException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }

  public String getReasonPhrase() {
    return getReason() + (getMessage() != null ? " (" + getMessage() + ")" : "");
  }

  @Override
  public String toString() {
    return NotificationProcessingException.class.getSimpleName()
        + "{"
        + "reason="
        + reason
        + ", message='"
        + getMessage()
        + '\''
        + '}';
  }
}
