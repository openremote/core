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
import { css, html, unsafeCSS } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { type AppStateKeyed, Page, type PageProvider } from "@openremote/or-app";
import type { Store } from "@reduxjs/toolkit";
import {
  type Asset,
  ClientRole,
  type Notification,
  NotificationSource,
  NotificationTargetType,
  type SentNotification,
  SentNotificationSortField,
  type ManagerAppRealmNotificationConfig,
  type User,
  getRealmNotificationConfig,
} from "@openremote/model";
import manager, { DefaultColor3 } from "@openremote/core";
import { i18next } from "@openremote/or-translate";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import type { AxiosError } from "@openremote/rest";
import type { OrVaadinSelect, SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import { OrVaadinDateTimePicker } from "@openremote/or-vaadin-components/or-vaadin-date-time-picker";
import "@openremote/or-vaadin-components/or-vaadin-button";
import {
  dialogRenderer,
  dialogFooterRenderer,
  dialogHeaderRenderer,
} from "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import type { OrNotificationForm } from "../components/notifications/or-notification-form";
import "../components/notifications/or-notification-form";
import type {
  NotificationTableClickEvent,
  OrNotificationsPageChangedEvent,
  OrNotificationsSortChangedEvent,
} from "../components/notifications/or-notifications-table";
import "../components/notifications/or-notifications-table";

type NotificationSourceKeys = keyof typeof NotificationSource;
const sources = [
  NotificationSource.INTERNAL,
  NotificationSource.CLIENT,
  NotificationSource.GLOBAL_RULESET,
  NotificationSource.REALM_RULESET,
  // NotificationSource.ASSET_RULESET, // Not implemented
] satisfies NotificationSourceKeys[];

export class NotificationService {
  async getNotifications(
    realm: string,
    fromDate?: number,
    toDate?: number,
    source?: NotificationSource,
    offset?: number,
    limit?: number,
    sort?: SentNotificationSortField,
    descending?: boolean,
    signal?: AbortSignal
  ): Promise<SentNotification[]> {
    try {
      const timeRange = fromDate && toDate ? { fromDate, toDate } : this.getDefaultTimeRange();

      const response = await manager.rest.api.NotificationResource.getNotifications(
        {
          from: timeRange.fromDate,
          to: timeRange.toDate,
          realmId: realm,
          source,
          sort,
          descending,
          offset,
          limit,
        },
        { signal }
      );

      if (!response.data) {
        console.warn("No data in response:", response);
        return [];
      }

      return response.data;
    } catch (err: unknown) {
      if (!signal?.aborted) {
        console.error("Failed to fetch notifications:", err);
      }
      throw err;
    }
  }

  async getNotificationsCount(
    realm: string,
    fromDate?: number,
    toDate?: number,
    source?: NotificationSource,
    signal?: AbortSignal
  ): Promise<number> {
    try {
      const timeRange = fromDate && toDate ? { fromDate, toDate } : this.getDefaultTimeRange();

      const response = await manager.rest.api.NotificationResource.getNotificationsCount(
        {
          from: timeRange.fromDate,
          to: timeRange.toDate,
          realmId: realm,
          source,
        },
        { signal }
      );

      return typeof response.data === "number" ? response.data : 0;
    } catch (err: unknown) {
      if (!signal?.aborted) {
        console.error("Failed to fetch notification count:", err);
      }
      throw err;
    }
  }

  async sendNotification(notification: Notification): Promise<boolean> {
    try {
      const response = await manager.rest.api.NotificationResource.sendNotification(notification);
      return response.status === 200;
    } catch (err: unknown) {
      const error = err as AxiosError;
      console.error("Failed to send notification:", error);
      throw error;
    }
  }

  async getAssetsDetails(assetIds: string[]): Promise<Asset[]> {
    const response = await manager.rest.api.AssetResource.queryAssets({ ids: assetIds });
    return response.data || [];
  }

  async getUsersDetails(userIds: string[]): Promise<User[]> {
    const response = await manager.rest.api.UserResource.query({ ids: userIds });
    return response.data || [];
  }

  hasUserReadPermissions(): boolean {
    return manager.hasRole(ClientRole.READ_USERS) || manager.hasRole(ClientRole.READ_ADMIN);
  }

  hasAssetReadPermissions(): boolean {
    return manager.hasRole(ClientRole.READ_ASSETS) || manager.hasRole(ClientRole.READ_ADMIN);
  }

  public getDefaultTimeRange(): { fromDate: number; toDate: number } {
    const now = new Date();
    const toDate = new Date(now);
    toDate.setHours(23, 59, 59, 999);

    // default to the last day only (beginning of today); the range can be extended manually
    const fromDate = new Date(now);
    fromDate.setHours(0, 0, 0, 0);

    const fromTimestamp = fromDate.getTime();
    const toTimestamp = toDate.getTime();

    if (!Number.isInteger(fromTimestamp) || !Number.isInteger(toTimestamp)) {
      console.error("Invalid timestamp generation:", { fromTimestamp, toTimestamp });
      throw new Error("Invalid timestamp generation");
    }

    return {
      fromDate: fromTimestamp,
      toDate: toTimestamp,
    };
  }
}

export interface PageNotificationsConfig {
  targetId?: string;
  targetType?: string;
  fromTimestamp?: number;
  toTimestamp?: number;
  notifications?: { [realm: string]: ManagerAppRealmNotificationConfig };
}

export function pageNotificationsProvider(
  store: Store<AppStateKeyed>,
  config?: PageNotificationsConfig
): PageProvider<AppStateKeyed> {
  return {
    name: "notifications",
    routes: ["notifications"],
    pageCreator: () => {
      return new PageNotifications(store, config);
    },
  };
}

@customElement("page-notifications")
export class PageNotifications extends Page<AppStateKeyed> {
  static get styles() {
    return [
      css`
        :host {
          flex: 1;
          width: 100%;
        }

        #wrapper {
          height: 100%;
          width: 100%;
          display: flex;
          flex-direction: column;
          overflow: auto;
        }

        #title {
          padding: 0 20px;
          font-size: 18px;
          font-weight: bold;
          margin: 10px 0;
          display: flex;
          align-items: center;
          color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
          justify-content: space-between;
          gap: var(--lumo-space-m);
        }

        /* Once the controls no longer fit beside the title, stack them underneath it. Switching to a
                   column (rather than flex-wrapping) keeps the title centered against the controls while inline
                   and avoids the title shifting up when they move below. */
        @media only screen and (max-width: 1360px) {
          #title {
            flex-direction: column;
            align-items: flex-start;
          }
        }

        #table-container {
          flex: 1;
          min-height: 0;
          display: flex;
          flex-direction: column;
          margin: 0 20px 20px 20px;
        }

        /* Table sizes to its rows but may shrink (and scroll internally) when it hits the bottom of the page */
        #table-container or-notifications-table {
          flex: 0 1 auto;
          min-height: 0;
        }

        .controls {
          display: flex;
          flex-wrap: wrap;
          align-items: flex-end;
          /* gap supplies the row spacing too, so wrapped fields keep their spacing below */
          gap: var(--lumo-space-m);
        }

        .controls or-vaadin-select {
          width: 180px;
        }

        .controls or-vaadin-date-time-picker {
          width: 280px;
          &::part(label) {
            padding-bottom: 0.5em; /* TODO: find a structural fix */
            /* "from"/"to" share lowercase i18n keys with inline usages elsewhere; capitalize here */
            text-transform: capitalize;
          }
        }
      `,
    ];
  }

  @property()
  protected realm?: string;

  @state()
  protected _data?: SentNotification[];

  @state()
  protected notification?: SentNotification;

  @state()
  protected _fromDate?: number;

  @state()
  protected _toDate?: number;

  @state()
  protected _dateRange?: number[];

  @state()
  protected _selectedSource?: string;

  @state()
  protected _isFilteredDate: boolean = false;

  @state()
  protected _isFilteredSource: boolean = false;

  @state()
  protected _currentPage: number = 0;

  @state()
  protected _totalCount: number = 0;

  @state()
  protected _pageSize: number = 10;

  // Server-side sort; defaults to newest-sent first (matches the table's default sort indicator on "Sent")
  protected _sortField: SentNotificationSortField = SentNotificationSortField.SENT_ON;
  protected _sortDescending: boolean = true;

  @state()
  protected _createDialogOpen: boolean = false;

  @state()
  protected _detailsDialogOpen: boolean = false;

  @state()
  protected _createFormValid: boolean = false;

  protected _createForm?: OrNotificationForm;

  protected _loading: boolean = false;
  protected _loadAbortController?: AbortController;

  protected notificationService: NotificationService;

  constructor(
    store: Store<AppStateKeyed>,
    protected config?: PageNotificationsConfig
  ) {
    super(store);
    this.notificationService = new NotificationService();

    // set initial date range
    const { fromDate, toDate } = this.notificationService.getDefaultTimeRange();
    this._fromDate = fromDate;
    this._toDate = toDate;
  }

  public stateChanged(state: AppStateKeyed): void {
    if (state.app.page === "notifications") {
      const realmChanged = this.realm !== state.app.realm;
      this.realm = state.app.realm;
      this.requestUpdate("realm");

      // Only fetch when relevant state changed; stateChanged fires on every store update
      // (e.g. visibility changes during page unload) which shouldn't trigger requests
      if (realmChanged) {
        this.reset();
        this._loadData();
      } else if (!this._data && !this._loading) {
        // Initial load when entering the page
        this._loadData();
      }
    }
  }

  protected reset(): void {
    this._data = undefined;
    this._totalCount = 0;
    this._currentPage = 0;
    this.requestUpdate();
  }

  get name(): string {
    return "notification_other";
  }

  protected async _loadData() {
    if (!this.realm) {
      return;
    }
    this._loadAbortController?.abort();
    const abortController = new AbortController();
    this._loadAbortController = abortController;
    this._loading = true;

    try {
      const timeRange =
        this._isFilteredDate && this._fromDate && this._toDate
          ? {
              fromDate: this._fromDate,
              toDate: this._toDate,
            }
          : this.notificationService.getDefaultTimeRange();

      if (!this._isFilteredDate) {
        this._fromDate = timeRange.fromDate;
        this._toDate = timeRange.toDate;
      }

      if (!this._isFilteredSource) {
        this._selectedSource = "ALL_SOURCES";
      }

      // Filter on source in the query so it applies to all pages rather than the current one
      const source =
        this._selectedSource && this._selectedSource !== "ALL_SOURCES"
          ? (this._selectedSource as NotificationSource)
          : undefined;

      const offset = this._currentPage * this._pageSize;

      const [data, count] = await Promise.all([
        this.notificationService.getNotifications(
          this.realm,
          timeRange.fromDate,
          timeRange.toDate,
          source,
          offset,
          this._pageSize,
          this._sortField,
          this._sortDescending,
          abortController.signal
        ),
        this.notificationService.getNotificationsCount(
          this.realm,
          timeRange.fromDate,
          timeRange.toDate,
          source,
          abortController.signal
        ),
      ]);

      this._data = data;
      this._totalCount = count;

      this.requestUpdate();
    } catch (err: unknown) {
      if (abortController.signal.aborted) {
        return;
      }
      console.error("Failed to load notifications:", err);
      showSnackbar(undefined, i18next.t("loadingNotificationFailed"));
    } finally {
      // Only the latest request may clear the loading flag
      if (this._loadAbortController === abortController) {
        this._loading = false;
      }
    }
  }

  protected async _handleCreateNotification() {
    const notification = this._createForm?.getNotification();

    // validate required fields per schema
    if (!notification) {
      showSnackbar(undefined, i18next.t("notifications.missingFields"));
      return;
    }

    try {
      await this.notificationService.sendNotification(notification);

      showSnackbar(undefined, i18next.t("notifications.successfullySentNotification"));
      this._createDialogOpen = false;
      // Reload explicitly: setting _createDialogOpen=false above means the @opened-changed handler's
      // guard is already false, so it won't reload for us. Without this the new notification only
      // appears after the next refetch (e.g. a filter change).
      await this._loadData();
    } catch (_error) {
      showSnackbar(undefined, i18next.t("notifications.failedToCreateNotification"));
    }
  }

  protected isValidTargetType(type: string): boolean {
    return (
      type === NotificationTargetType.REALM ||
      type === NotificationTargetType.USER ||
      type === NotificationTargetType.ASSET ||
      type === NotificationTargetType.CUSTOM
    );
  }

  protected render() {
    const writeNotifications = manager.hasRole(ClientRole.WRITE_NOTIFICATIONS);
    const hasRecipientType =
      manager.hasRole(ClientRole.READ_ADMIN) ||
      manager.hasRole(ClientRole.READ_ASSETS) ||
      manager.hasRole(ClientRole.READ_USERS);

    return html`
      <div id="wrapper">
        ${html` ${this._renderHeader(writeNotifications, hasRecipientType)}
          <div id="table-container">${this._renderNotificationsTable()}</div>`}
      </div>
      ${this._renderCreateDialog()} ${this._renderDetailsDialog()}
    `;
  }

  protected _renderCreateDialog() {
    return html`
      <or-vaadin-dialog
        id="createDialog"
        width="1024px"
        height="976px"
        no-close-on-outside-click
        no-close-on-esc
        header-title="${i18next.t("notifications.createNotification")}"
        ?opened="${this._createDialogOpen}"
        @opened-changed="${(ev: CustomEvent) => {
          // Safety net for dialog-initiated closes; Cancel and a successful create close
          // (and reload) through their own handlers, which this guard skips
          if (!ev.detail.value && this._createDialogOpen) {
            this._createDialogOpen = false;
            this._loadData();
          }
        }}"
        ${dialogHeaderRenderer(
          () => html`
            <or-vaadin-button theme="tertiary" @click="${() => this._cancelCreateDialog()}">
              <or-icon icon="mdi:close"></or-icon>
            </or-vaadin-button>
          `,
          []
        )}
        ${dialogRenderer(() => this._renderCreateForm(), [this.realm])}
        ${dialogFooterRenderer(
          () => html`
            <or-vaadin-button theme="tertiary contrast" @click="${() => this._cancelCreateDialog()}">
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button
              theme="primary"
              ?disabled="${!this._createFormValid}"
              @click="${() => this._handleCreateNotification()}"
            >
              <or-translate value="create"></or-translate>
            </or-vaadin-button>
          `,
          [this._createFormValid]
        )}
      >
      </or-vaadin-dialog>
    `;
  }

  protected _renderDetailsDialog() {
    return html`
      <or-vaadin-dialog
        id="detailsDialog"
        width="1024px"
        header-title="${i18next.t("notifications.details")}"
        ?opened="${this._detailsDialogOpen}"
        @opened-changed="${(ev: CustomEvent) => {
          if (!ev.detail.value && this._detailsDialogOpen) {
            this._detailsDialogOpen = false;
            this._loadData();
          }
        }}"
        ${dialogHeaderRenderer(
          () => html`
            <or-vaadin-button theme="tertiary" @click="${() => (this._detailsDialogOpen = false)}">
              <or-icon icon="mdi:close"></or-icon>
            </or-vaadin-button>
          `,
          []
        )}
        ${dialogRenderer(() => this._renderDetailsForm(this.notification), [this.notification, this.realm])}
      >
      </or-vaadin-dialog>
    `;
  }

  // The form stays mounted across opens (so closing the dialog doesn't visibly clear it before it animates out);
  // _showCreateDialog calls form.reset() on each open instead, which clears prior input and reloads the active
  // realm's users/assets/realms.
  protected _renderCreateForm() {
    return html`
      <or-notification-form
        id="notificationForm"
        .realm=${this.realm}
        @or-notification-form-changed="${(ev: Event) => this._onCreateFormChanged(ev)}"
      >
      </or-notification-form>
    `;
  }

  protected _renderDetailsForm(notification?: SentNotification) {
    return html`
      <or-notification-form
        .realm="${this.realm}"
        .notification="${notification}"
        .notificationConfig="${getRealmNotificationConfig(this.config?.notifications, this.realm)}"
        readonly
      >
      </or-notification-form>
    `;
  }

  protected _onCreateFormChanged(ev: Event) {
    this._createForm = ev.target as OrNotificationForm;
    const valid = !!this._createForm.getNotification();
    if (valid !== this._createFormValid) {
      this._createFormValid = valid;
    }
  }

  protected _renderHeader(writeNotifications: boolean, hasRecipientType: boolean = true) {
    const sourceOptions: SelectItem[] = [
      { label: i18next.t("notifications.sources.ALL_SOURCES"), value: "ALL_SOURCES" },
      ...sources.map((s) => ({ label: i18next.t(`notifications.sources.${s}`), value: s as string })),
    ];

    return html`
      <div id="title">
        <div style="display: flex; align-items: center; min-height: 60px;">
          <or-icon icon="message-outline" style="padding: 0 10px 0 4px;"></or-icon>
          <span><or-translate value="notification_other" /></span>
        </div>

        <div class="controls">
          <or-vaadin-select
            .items="${sourceOptions}"
            value="${this._selectedSource || "ALL_SOURCES"}"
            @change="${(ev: Event) => {
              this._selectedSource = (ev.currentTarget as OrVaadinSelect).value;
              this._isFilteredSource = true; // set filter changes
              this._currentPage = 0;
              this._loadData();
            }}"
          >
            <or-translate slot="label" value="source"></or-translate>
          </or-vaadin-select>

          <or-vaadin-date-time-picker
            value="${this._fromDate ? OrVaadinDateTimePicker.getLocalizedISOString(new Date(this._fromDate)) : ""}"
            @change="${(ev: Event) => {
              const value = (ev.currentTarget as OrVaadinDateTimePicker).value;
              if (!value) return;
              this._fromDate = new Date(value).getTime();
              this._isFilteredDate = true;
              this._currentPage = 0;
              this._loadData();
            }}"
          >
            <or-translate slot="label" value="from"></or-translate>
          </or-vaadin-date-time-picker>

          <or-vaadin-date-time-picker
            value="${this._toDate ? OrVaadinDateTimePicker.getLocalizedISOString(new Date(this._toDate)) : ""}"
            @change="${(ev: Event) => {
              const value = (ev.currentTarget as OrVaadinDateTimePicker).value;
              if (!value) return;
              this._toDate = new Date(value).getTime();
              this._isFilteredDate = true;
              this._currentPage = 0;
              this._loadData();
            }}"
          >
            <or-translate slot="label" value="to"></or-translate>
          </or-vaadin-date-time-picker>

          ${
            writeNotifications
              ? html`
                  <or-vaadin-button ?disabled="${!hasRecipientType}" @click="${() => this._showCreateDialog()}">
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="notifications.sendNew"></or-translate>
                  </or-vaadin-button>
                `
              : ``
          }
        </div>
      </div>
    `;
  }

  protected _renderNotificationsTable() {
    const notifications = this._data || [];
    return html`
      <or-notifications-table
        .notifications=${notifications}
        .notificationService=${this.notificationService}
        .totalCount=${this._totalCount}
        .currentPage=${this._currentPage}
        .paginationSize=${this._pageSize}
        @or-notifications-page-changed="${(e: OrNotificationsPageChangedEvent) => {
          this._currentPage = e.detail.page;
          this._pageSize = e.detail.size;
          this._loadData();
        }}"
        @or-notifications-sort-changed="${(e: OrNotificationsSortChangedEvent) => {
          this._sortField = e.detail.sort;
          this._sortDescending = e.detail.descending;
          this._currentPage = 0; // sorting spans all pages, so restart from the first
          this._loadData();
        }}"
        @or-notification-selected="${(e: NotificationTableClickEvent) => this._onRowClick(e)}"
      ></or-notifications-table>
      ${
        !this._loading && notifications && notifications.length === 0
          ? html`
              <div style="padding: 20px; text-align: center; font-style: italic; color: var(--or-app-color3);">
                <or-translate value="noResults"></or-translate>
              </div>
            `
          : ""
      }
    `;
  }

  protected _showCreateDialog() {
    // The form persists between opens, so clear any input left over from a previous open
    this._createForm?.reset();
    this._createFormValid = false;
    this._createDialogOpen = true;
  }

  protected _cancelCreateDialog() {
    this._createDialogOpen = false;
    // A failed send may still have persisted the notification with its error, so refresh the table
    this._loadData();
  }

  private _onRowClick(e: NotificationTableClickEvent) {
    if (!this._data || !e.detail) {
      console.warn("No data available.");
      return;
    }
    const notificationId = e.detail.notificationId;

    const notification = this._data.find((n) => n.id === notificationId);

    if (!notification) {
      console.warn(`No notification found with id ${notificationId}`);
      return;
    }

    this.notification = notification;
    this._detailsDialogOpen = true;
  }
}
