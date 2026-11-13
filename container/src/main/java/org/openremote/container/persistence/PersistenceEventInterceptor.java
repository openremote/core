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
package org.openremote.container.persistence;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.camel.CamelExecutionException;
import org.hibernate.CallbackException;
import org.hibernate.Interceptor;
import org.hibernate.Transaction;
import org.hibernate.type.Type;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;

/** Intercept Hibernate lifecycle events and publish a message. */
public class PersistenceEventInterceptor implements Interceptor {

  private static final Logger LOG = Logger.getLogger(PersistenceEventInterceptor.class.getName());
  protected Consumer<PersistenceEvent<?>> eventConsumer;
  protected Set<PersistenceEvent<?>> persistenceEvents = new HashSet<>();

  public void setEventConsumer(Consumer<PersistenceEvent<?>> eventConsumer) {
    this.eventConsumer = eventConsumer;
  }

  @Override
  public boolean onPersist(
      Object entity, Object id, Object[] state, String[] propertyNames, Type[] types)
      throws CallbackException {
    persistenceEvents.add(
        new PersistenceEvent<>(PersistenceEvent.Cause.CREATE, entity, propertyNames, state));
    return false;
  }

  @Override
  public boolean onFlushDirty(
      Object entity,
      Object id,
      Object[] currentState,
      Object[] previousState,
      String[] propertyNames,
      Type[] types)
      throws CallbackException {
    if (isAssetDeletePendingUpdate(entity, currentState, previousState, propertyNames)) {
      // Assets are soft deleted but we still want a delete event to maintain existing behavior
      persistenceEvents.add(
          new PersistenceEvent<>(PersistenceEvent.Cause.DELETE, entity, null, currentState));
    } else {
      persistenceEvents.add(
          new PersistenceEvent<>(
              PersistenceEvent.Cause.UPDATE, entity, propertyNames, currentState, previousState));
    }
    return false;
  }

  protected boolean isAssetDeletePendingUpdate(
      Object entity, Object[] currentState, Object[] previousState, String[] propertyNames) {
    if (!(entity instanceof Asset<?>)
        || currentState == null
        || previousState == null
        || propertyNames == null) {
      return false;
    }

    for (int i = 0; i < propertyNames.length; i++) {
      if (i < currentState.length
          && i < previousState.length
          && Asset.DELETE_PENDING_PROPERTY_NAME.equals(propertyNames[i])) {
        return Boolean.TRUE.equals(currentState[i]) && !Boolean.TRUE.equals(previousState[i]);
      }
    }

    return false;
  }

  @Override
  public void onRemove(
      Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
    PersistenceEvent.Cause cause = PersistenceEvent.Cause.DELETE;
    if (entity instanceof Asset<?>) {
      // Assets are soft deleted first; physical removal is signalled separately.
      cause = PersistenceEvent.Cause.DELETE_FINISHED;
    }
    persistenceEvents.add(new PersistenceEvent<>(cause, entity, propertyNames, state));
  }

  @Override
  public void afterTransactionBegin(Transaction tx) {
    tx.registerSynchronization(
        new Synchronization() {
          @Override
          public void beforeCompletion() {}

          @Override
          public void afterCompletion(int status) {
            try {
              if (status != Status.STATUS_COMMITTED) return;

              if (eventConsumer == null) {
                // Event consumer not set
                return;
              }

              for (PersistenceEvent<?> persistenceEvent : persistenceEvents) {
                try {
                  eventConsumer.accept(persistenceEvent);
                } catch (CamelExecutionException ex) {
                  // TODO Better error handling?
                  LOG.log(Level.SEVERE, "Error dispatching: " + persistenceEvent + " - " + ex, ex);
                }
              }
            } finally {
              persistenceEvents.clear();
            }
          }
        });
  }
}
