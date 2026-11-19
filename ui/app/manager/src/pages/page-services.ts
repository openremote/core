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
import { css, html, type PropertyValues, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { type AppStateKeyed, Page, type PageProvider, router } from "@openremote/or-app";
import type { Store } from "@reduxjs/toolkit";
import { manager } from "@openremote/core";
import "@openremote/or-services";
import {
  type ExternalService,
  type ExternalServiceEvent,
  ExternalServiceEventCause,
  ExternalServiceStatus,
} from "@openremote/model";
import { getServicesRoute } from "../routes";

import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { i18next } from "@openremote/or-translate";

export function pageServicesProvider(store: Store<AppStateKeyed>): PageProvider<AppStateKeyed> {
  return {
    name: "services",
    routes: ["services", "services/:serviceName"],
    pageCreator: () => {
      const page = new PageServices(store);
      return page;
    },
  };
}

const serviceStyles = css`
  :host {
    overflow: hidden;
  }

  #services {
    z-index: 0;
    background: transparent;
  }
`;

/**
 * Consolidate services by serviceId, preferring AVAILABLE over UNAVAILABLE
 * @param services - Array of services to consolidate
 * @returns Consolidated array with unique serviceIds
 */
function consolidateServices(services: ExternalService[]): ExternalService[] {
  return Object.values(
    services.reduce(
      (acc, service) => {
        const serviceId = service.serviceId || "";
        const existing = acc[serviceId];
        if (
          !existing ||
          (service.status === ExternalServiceStatus.AVAILABLE && existing.status !== ExternalServiceStatus.AVAILABLE)
        ) {
          acc[serviceId] = service;
        }
        return acc;
      },
      {} as Record<string, ExternalService>
    )
  );
}

@customElement("page-services")
export class PageServices extends Page<AppStateKeyed> {
  static get styles() {
    return [serviceStyles];
  }

  get name(): string {
    return "services";
  }

  @state()
  protected serviceId: string | null = null;

  @state()
  protected realmName = "";

  @state()
  protected services: ExternalService[] = [];

  @state()
  protected selectedService: ExternalService | null = null;

  @state()
  protected _loading = false;

  protected _eventSubscriptionId?: string;

  constructor(store: Store<AppStateKeyed>) {
    super(store);
    this.realmName = manager.displayRealm;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this._subscribeEvents();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this._unsubscribeEvents();
  }

  protected async _subscribeEvents() {
    if (manager.events) {
      this._eventSubscriptionId = await manager.events.subscribe<ExternalServiceEvent>(
        {
          eventType: "external-service",
        },
        (ev) => this._onEvent(ev)
      );
    }
  }

  protected _unsubscribeEvents() {
    if (this._eventSubscriptionId) {
      manager.events?.unsubscribe(this._eventSubscriptionId);
    }
  }

  protected _onEvent(event: ExternalServiceEvent) {
    if (!event.service || !event.cause) {
      return;
    }

    // Super users receive all events, so we need to filter them out if they are not for the display realm or global
    if (manager.isSuperUser && event.service.realm !== this.realmName && !event.service.isGlobal) {
      return;
    }

    // Handle the event based on the cause
    switch (event.cause) {
      case ExternalServiceEventCause.REGISTER: {
        this._onServiceRegistered(event);
        break;
      }
      case ExternalServiceEventCause.UPDATE: {
        this._onServiceUpdated(event);
        break;
      }
      case ExternalServiceEventCause.DEREGISTER: {
        this._onServiceDeregistered(event);
        break;
      }
      default:
        break;
    }

    this.services = consolidateServices(this.services);
  }

  protected _onServiceRegistered(event: ExternalServiceEvent): void {
    this.services.push(event.service);

    // If the pushed service is the same as the selected service.serviceId and the current selected service is unvailable, refresh the iframe
    if (
      this._eventIsForSelectedServiceId(event) &&
      this.selectedService?.status === ExternalServiceStatus.UNAVAILABLE
    ) {
      this.selectedService = event.service;
      this._refreshIframe();
    }
  }

  protected _onServiceUpdated(event: ExternalServiceEvent): void {
    const existingServiceInstance = this.services.find(
      (service) => service.serviceId === event.service?.serviceId && service.instanceId === event.service?.instanceId
    );

    // Overwrite the existing service with the updated service object
    if (existingServiceInstance) {
      Object.assign(existingServiceInstance, event.service);
    }

    // If the selected service instance is the one that was updated, and the status has changed, refresh the iframe
    if (this._eventIsForSelectedServiceInstance(event) && this.selectedService?.status !== event.service?.status) {
      this.selectedService = event.service;
      this._refreshIframe();
      return;
    }

    // If the selected serviceId is the same as the event serviceId and the current selected status is unavailable, refresh the iframe
    if (
      this._eventIsForSelectedServiceId(event) &&
      this.selectedService?.status === ExternalServiceStatus.UNAVAILABLE
    ) {
      this.selectedService = event.service;
      this._refreshIframe();
    }
  }

  protected _onServiceDeregistered(event: ExternalServiceEvent): void {
    // Filter out the service instance that was deregistered
    this.services = this.services.filter(
      (service) => service.serviceId !== event.service?.serviceId && service.instanceId !== event.service?.instanceId
    );

    // If the selected service instance is the one that was deregistered, set the selected service to null
    if (this._eventIsForSelectedServiceInstance(event)) {
      this.selectedService = null;
    }
  }

  protected _eventIsForSelectedServiceInstance(event: ExternalServiceEvent): boolean {
    return (
      this.selectedService?.serviceId === event.service?.serviceId &&
      this.selectedService?.instanceId === event.service?.instanceId
    );
  }

  protected _eventIsForSelectedServiceId(event: ExternalServiceEvent): boolean {
    return this.selectedService?.serviceId === event.service?.serviceId;
  }

  protected async _loadData(silent = false): Promise<void> {
    if (!silent) {
      this._loading = true;
    }

    try {
      // Use promise.allSettled to run both requests in parallel and wait for both to complete
      // If we use Promise.all, we would not be able to handle the case where one request fails and the other succeeds
      const [realmServicesResult, globalServicesResult] = await Promise.allSettled([
        manager.rest.api.ExternalServiceResource.getServices({
          realm: this.realmName,
        }),
        manager.rest.api.ExternalServiceResource.getGlobalServices(),
      ]);

      // Temporary array to store all retrieved services
      const retrievedServices: ExternalService[] = [];

      const realmServicesRequestHasSucceeded =
        realmServicesResult.status === "fulfilled" && realmServicesResult.value.status === 200;

      // Handle realm services result
      if (realmServicesRequestHasSucceeded) {
        retrievedServices.push(...realmServicesResult.value.data);
      } else {
        console.error("Failed to load realm services:", realmServicesResult.status);
        showSnackbar(undefined, i18next.t("service.realmServicesLoadError"));
      }

      // Handle global services result
      const globalServicesRequestHasSucceeded =
        globalServicesResult.status === "fulfilled" && globalServicesResult.value.status === 200;
      if (globalServicesRequestHasSucceeded) {
        retrievedServices.push(...globalServicesResult.value.data);
      } else {
        console.error("Failed to load global services:", globalServicesResult.status);
        showSnackbar(undefined, i18next.t("service.globalServicesLoadError"));
      }

      // Update the services state if we able to retrieve any
      if (retrievedServices.length > 0) {
        this.services = consolidateServices(retrievedServices);
        this.requestUpdate();

        // Re-select the service if serviceId is set and no service is selected
        if (this.serviceId && !this.selectedService) {
          const service = this.services.find((service) => service.serviceId === this.serviceId);
          if (service) {
            this.selectedService = service;
          }
        }
      }
    } catch (error) {
      console.error("Unexpected error loading services:", error);
      showSnackbar(undefined, i18next.t("service.servicesLoadError"));
    } finally {
      this._loading = false;
    }
  }

  // Reload the current service iframe
  protected _refreshIframe(): void {
    const servicesComponent = this.shadowRoot?.querySelector("or-services") as any;
    if (servicesComponent && typeof servicesComponent.refreshIframe === "function") {
      servicesComponent.refreshIframe();
    } else {
      console.warn("Unable to reload iframe: services component not found or refreshIframe method not available");
    }
  }

  public stateChanged(state: AppStateKeyed): void {
    this.realmName = this.realmSelector(state);

    // Update serviceId from URL params - this will trigger updated() which handles the selection
    const newServiceId = state.app.params?.serviceName;
    if (newServiceId !== this.serviceId) {
      this.serviceId = newServiceId;
    }
  }

  protected realmSelector = (state: AppStateKeyed) => state.app.realm || manager.config.realm;

  protected _onServiceSelected(e: CustomEvent): void {
    const service = e.detail as ExternalService;

    if (service && service.serviceId !== this.serviceId) {
      this.serviceId = service.serviceId;
      this.selectedService = service;
      this._updateRoute();
    }
  }

  protected willUpdate(changedProperties: PropertyValues) {
    super.willUpdate(changedProperties);

    if (changedProperties.has("serviceId")) {
      if (this.serviceId) {
        this._updateServiceSelection();
      }
    }

    if (changedProperties.has("realmName")) {
      this._loadData();
    }
  }

  protected _updateRoute() {
    router.navigate(getServicesRoute(this.serviceId));
  }

  protected _updateServiceSelection() {
    if (this.serviceId) {
      const service = this.services.find((service) => service.serviceId === this.serviceId);
      if (service) {
        this.selectedService = service;
      }
    } else {
      this.selectedService = null;
    }
  }

  protected render(): TemplateResult {
    return html`
      <div style="width: 100%;">
        <or-services
          id="services"
          .realmName="${this.realmName}"
          .services="${this.services}"
          .selectedService="${this.selectedService}"
          .loading="${this._loading}"
          @or-service-selected="${this._onServiceSelected}"
        ></or-services>
      </div>
    `;
  }
}
