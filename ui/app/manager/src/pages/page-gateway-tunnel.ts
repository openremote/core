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
import { type AppStateKeyed, Page, type PageProvider, router } from "@openremote/or-app";
import { css, html, type TemplateResult, unsafeCSS } from "lit";
import { customElement, state } from "lit/decorators.js";
import { until } from "lit/directives/until.js";
import { when } from "lit/directives/when.js";
import { Task } from "@lit/task";
import type { Store } from "@reduxjs/toolkit";
import { i18next } from "@openremote/or-translate";
import { DefaultColor3, manager } from "@openremote/core";
import { type Asset, type AssetQuery, type GatewayTunnelInfo, GatewayTunnelInfoType } from "@openremote/model";
import type { TableColumn, TableRow } from "@openremote/or-mwc-components/or-mwc-table";
import { getAssetsRoute } from "../routes";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import moment from "moment";
import type { OrVaadinComboBox } from "@openremote/or-vaadin-components/or-vaadin-combo-box";
import type { OrVaadinSelect } from "@openremote/or-vaadin-components/or-vaadin-select";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import { createRef, ref, type Ref } from "lit/directives/ref.js";

export function pageGatewayTunnelProvider(store: Store<AppStateKeyed>): PageProvider<AppStateKeyed> {
  return {
    name: "gateway-tunnel",
    routes: ["gateway-tunnel"],
    pageCreator: () => {
      return new PageGatewayTunnel(store);
    },
  };
}

const styling = css`
  #wrapper {
    height: 100%;
    width: 100%;
    display: flex;
    flex-direction: column;
  }

  #title {
    padding: 0 20px;
    font-size: 18px;
    font-weight: bold;
    width: calc(100% - 40px);
    max-width: 1360px;
    margin: 20px auto;
    align-items: center;
    display: flex;
    color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
  }

  #title > or-icon {
    margin-right: 10px;
    margin-left: 14px;
  }

  .panel {
    flex: 0;
    width: 100%;
    box-sizing: border-box;
    max-width: 1360px;
    background-color: white;
    border: 1px solid #e5e5e5;
    border-radius: 5px;
    position: relative;
    margin: 0 auto 10px;
    padding: 12px 24px 24px;
    display: flex;
    flex-direction: column;
  }

  .panel-title {
    display: flex;
    text-transform: uppercase;
    font-weight: bolder;
    color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
    line-height: 1em;
    margin-bottom: 10px;
    margin-top: 0;
    flex: 0 0 auto;
    letter-spacing: 0.025em;
    align-items: center;
    min-height: 36px;
  }
`;

@customElement("page-gateway-tunnel")
export class PageGatewayTunnel extends Page<AppStateKeyed> {
  // Static value with the asset types to be used
  protected GATEWAY_TUNNEL_TYPES = ["GatewayAsset"];

  // Static value with possible protocol types
  protected GATEWAY_TUNNEL_PROTOCOL_TYPES: GatewayTunnelInfoType[] = [
    GatewayTunnelInfoType.HTTPS,
    GatewayTunnelInfoType.HTTP,
    GatewayTunnelInfoType.TCP,
  ];

  @state()
  protected _realm = manager.displayRealm;

  @state()
  protected _loading = false;

  protected _refreshTimer?: number = undefined;

  static get styles() {
    return [styling];
  }

  disconnectedCallback() {
    if (this._refreshTimer) {
      clearTimeout(this._refreshTimer);
    }
    super.disconnectedCallback();
  }

  get name(): string {
    return "gateway-tunnel";
  }

  stateChanged(state: AppStateKeyed): void {
    this._realm = state.app.realm || manager.displayRealm;
  }

  protected render(): TemplateResult {
    return html`
      <div id="wrapper">
        <div id="title">
          <or-icon icon="lan-connect"></or-icon>
          ${i18next.t("gatewayTunnel")}
        </div>
        <div class="panel">
          <div class="panel-title" style="justify-content: space-between;">
            <or-translate value="tunnels"></or-translate>
            <or-vaadin-button @click=${() => this._onAddTunnelClick()}>
              <or-icon slot="prefix" icon="plus"></or-icon>
              <span>
                <or-translate value="add"></or-translate>
                <or-translate value="tunnel"></or-translate>
              </span>
            </or-vaadin-button>
          </div>
          ${this._fetchTunnelsTask.render({
            pending: () => html`${i18next.t("loading")}`,
            complete: (tunnels) => until(this.getTunnelsTable(tunnels), html`${i18next.t("loading")}`),
            error: () => html`${i18next.t("errorOccurred")}`,
          })}
        </div>
      </div>
    `;
  }

  /**
   * Function that returns an HTML {@link TemplateResult} with a table.
   * Displays the given {@link tunnels} parameter in a nice looking format.
   * Uses the {@link OrMwcTable} component for displaying
   */
  protected async getTunnelsTable(tunnels?: GatewayTunnelInfo[]): Promise<TemplateResult> {
    const columns: TableColumn[] = [
      { title: i18next.t("gatewayTunnels.id"), isSortable: true },
      { title: i18next.t("gatewayTunnels.gatewayId") },
      { title: i18next.t("gatewayTunnels.type"), isSortable: true },
      { title: i18next.t("gatewayTunnels.target"), isSortable: true, hideMobile: true },
      { title: i18next.t("gatewayTunnels.targetPort"), isSortable: true, hideMobile: true },
      { title: i18next.t("gatewayTunnels.assignedPort"), isSortable: true, hideMobile: true },
      { title: i18next.t("gatewayTunnels.closesAt"), isSortable: true, hideMobile: true },
      { title: "" },
    ];
    const rows: TableRow[] =
      tunnels?.map(
        (tunnel) =>
          ({
            content: [
              tunnel.id,
              until(this._getTunnelGatewayIdTemplate(tunnel), html`${i18next.t("loading")}`),
              tunnel.type,
              tunnel.target,
              tunnel.targetPort + "",
              !tunnel.assignedPort ? "" : tunnel.assignedPort + "",
              tunnel.autoCloseTime ? moment(tunnel.autoCloseTime).format("lll") : "",
              until(this._getTunnelActionsTemplate(tunnel), html`${i18next.t("loading")}`),
            ],
            clickable: false,
          }) as TableRow
      ) || [];
    return html` <or-mwc-table .columns="${columns}" .rows="${rows}"> </or-mwc-table> `;
  }

  /**
   * Function that returns an HTML {@link TemplateResult} containing the gateway asset that is linked to the {@link tunnel}.
   */
  protected async _getTunnelGatewayIdTemplate(tunnel: GatewayTunnelInfo): Promise<TemplateResult | string> {
    const onClick = () => {
      router.navigate(getAssetsRoute(false, tunnel.gatewayId));
    };
    return html`
      <div style="height: 100%; display: flex; align-items: center;">
        <span style="cursor: pointer;" @click="${() => onClick()}">${tunnel.gatewayId}</span>
      </div>
    `;
  }

  /**
   * Function that returns an HTML {@link TemplateResult} containing the actions for the given {@link tunnel}.
   */
  protected async _getTunnelActionsTemplate(tunnel: GatewayTunnelInfo): Promise<TemplateResult | string> {
    return html`
      <div style="display: flex; justify-content: end; align-items: center; gap: 12px;">
        <or-vaadin-button theme="icon" @click=${(ev: Event) => this._onStopTunnelClick(ev, tunnel)}>
          <or-icon icon="stop"></or-icon>
        </or-vaadin-button>
        ${when(
          tunnel.type === GatewayTunnelInfoType.TCP,
          () => html`
            <or-vaadin-button @click=${(ev: Event) => this._onCopyTunnelAddressClick(ev, tunnel)}>
              <or-translate value="gatewayTunnels.copyAddress"></or-translate>
            </or-vaadin-button>
          `,
          () => html`
            <or-vaadin-button @click=${(ev: Event) => this._onOpenTunnelClick(ev, tunnel)}>
              <or-translate value="gatewayTunnels.open"></or-translate>
            </or-vaadin-button>
          `
        )}
      </div>
    `;
  }

  /**
   * HTML callback event of the 'stop' button in the tunnels table,
   * meant to stop and delete the constructed tunnel.
   */
  protected _onStopTunnelClick(ev: Event, tunnel: GatewayTunnelInfo) {
    ev.stopPropagation();
    const onOk = () => {
      this._stopTunnel(tunnel)
        .then((success) => {
          if (success) {
            showSnackbar(undefined, "gatewayTunnels.closeSuccessful");
          } else {
            showSnackbar(undefined, "errorOccurred");
          }
        })
        .catch((_error) => {
          showSnackbar(undefined, "errorOccurred");
        })
        .finally(() => {
          this._fetchTunnelsTask.run();
        });
    };
    showConfirmDialog(
      this.shadowRoot,
      html`
        <or-vaadin-confirm-dialog @confirm=${() => onOk()}>
          ${getConfirmDialogContent("error", "areYouSure", "gatewayTunnels.stopWarning", "gatewayTunnels.stop", "cancel")}
        </or-vaadin-confirm-dialog>
      `
    );
  }

  /**
   * Function that requests the manager to stop the {@link tunnel}.
   */
  protected async _stopTunnel(tunnel: GatewayTunnelInfo): Promise<boolean> {
    const response = await manager.rest.api.GatewayServiceResource.stopTunnel(tunnel);
    return response.status === 204;
  }

  /**
   * HTML callback event for the 'copy address' button in the tunnels table,
   * meant for TCP addresses to be copied to the browsers' clipboard.
   */
  protected _onCopyTunnelAddressClick(ev: Event, tunnel: GatewayTunnelInfo): void {
    const address = this._getTunnelAddress(tunnel);
    if (address) {
      navigator.clipboard.writeText(address).finally(() => {
        showSnackbar(undefined, i18next.t("gatewayTunnels.copySuccess"));
      });
    } else {
      console.warn("Could not copy tunnel address as it could not be found.");
      showSnackbar(undefined, i18next.t("errorOccurred"));
    }
  }

  /**
   * HTML callback event of the 'open' button in the tunnels table,
   * meant to start tunneling towards that instance
   */
  protected _onOpenTunnelClick(ev: Event, tunnel: GatewayTunnelInfo): void {
    this._navigateToTunnel(tunnel);
  }

  /**
   * HTML callback event of the 'add' button above the tunnels table,
   * meant to start a tunnel towards that instance.
   */
  protected _onAddTunnelClick() {
    const tunnel = {} as GatewayTunnelInfo;
    let dialog: OrVaadinDialog | undefined;
    const buttonRef: Ref<OrVaadinButton> = createRef();

    const onAddClick = () => {
      this._tryStartTunnel(tunnel);
      dialog?.close();
    };

    const checkValidity = () => {
      buttonRef.value!.disabled =
        !tunnel.gatewayId ||
        (tunnel.hostname && tunnel.hostname.trim().length === 0) ||
        (tunnel.targetPort !== undefined && (tunnel.targetPort <= 0 || tunnel.targetPort > 65535));
    };

    const gatewayListTemplate = async (): Promise<TemplateResult> => {
      const gatewayAssets = await this._fetchGatewayAssets();
      const items: { value: any; label: string }[] = gatewayAssets.map((g) => ({ value: g.id, label: g.name }));
      const types: { value: any; label: string }[] = this.GATEWAY_TUNNEL_PROTOCOL_TYPES.map((g) => ({
        value: g,
        label: String(g),
      }));
      return html`
        <div style="display: flex; flex-direction: column; gap: 20px; width: 100%;">
          <or-vaadin-combo-box
            .items=${items}
            value="${tunnel.gatewayId}"
            @change=${(ev: Event) => {
              tunnel.gatewayId = (ev.currentTarget as OrVaadinComboBox).value;
              checkValidity();
            }}
          >
            <or-translate slot="label" value="gatewayTunnels.selectAsset"></or-translate>
          </or-vaadin-combo-box>
          <or-vaadin-select
            .items=${types}
            value=${tunnel.type}
            placeholder=${this.GATEWAY_TUNNEL_PROTOCOL_TYPES?.[0]}
            @change=${(ev: Event) => {
              tunnel.type = (ev.currentTarget as OrVaadinSelect).value as GatewayTunnelInfoType;
              checkValidity();
            }}
          >
            <or-translate slot="label" value="gatewayTunnels.protocol"></or-translate>
          </or-vaadin-select>
          <or-vaadin-text-field
            value=${tunnel.target}
            placeholder="localhost"
            @change=${(ev: Event) => {
              tunnel.target = (ev.currentTarget as HTMLInputElement).value;
              checkValidity();
            }}
          >
            <or-translate slot="label" value="host"></or-translate>
          </or-vaadin-text-field>
          <or-vaadin-number-field
            value=${tunnel.targetPort}
            placeholder="443"
            min="0"
            step="1"
            @change=${(ev: Event) => {
              const value = (ev.currentTarget as HTMLInputElement).value;
              tunnel.targetPort = value ? Number(value) : undefined;
              checkValidity();
            }}
          >
            <or-translate slot="label" value="port"></or-translate>
          </or-vaadin-number-field>
        </div>
      `;
    };
    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="add"></or-translate>
            <or-translate value="tunnel"></or-translate>
          </h2>
          ${until(gatewayListTemplate(), html`<or-translate value="loading"></or-translate>`)}
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" ${ref(buttonRef)} disabled @click=${() => onAddClick()}>
              <or-translate value="add"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  /**
   * Function that attempts to start a new {@link tunnel}, but requires checking of its fields beforehand,
   * making sure the correct details are sent towards the {@link _startTunnel()} method.
   */
  protected _tryStartTunnel(tunnel: GatewayTunnelInfo) {
    if (!tunnel) {
      console.warn("Tried to add tunnel, but failed.");
      return;
    }
    if (!tunnel.gatewayId) {
      console.warn("Tried to add tunnel, but gateway ID missing!");
      return;
    }
    if (!tunnel.realm) {
      tunnel.realm = manager.displayRealm;
    }
    this._startTunnel(tunnel)
      .catch((e) => {
        console.error(e);
        showSnackbar(undefined, i18next.t("errorOccurred"));
      })
      .finally(() => {
        this._fetchTunnelsTask.run();
      });
  }

  /**
   * Function that requests the manager to start a new {@link tunnel}.
   */
  protected async _startTunnel(tunnel: GatewayTunnelInfo): Promise<boolean> {
    const response = await manager.rest.api.GatewayServiceResource.startTunnel(tunnel);
    return response?.status === 200;
  }

  /**
   * Function that opens a web page through the tunnel (in a new browser tab)
   */
  protected _navigateToTunnel(info: GatewayTunnelInfo): void {
    if (!info.realm || !info.gatewayId || !info.target || !info.targetPort) {
      console.warn("Could not navigate to tunnel, as some provided information was not set.");
    }
    const address = this._getTunnelAddress(info);
    switch (info.type) {
      case GatewayTunnelInfoType.HTTPS:
      case GatewayTunnelInfoType.HTTP:
        window.open(address)?.focus();
        break;
      default:
        console.error("Unknown error when navigating to tunnel.");
        break;
    }
  }

  /**
   * Internal function to get the tunnel address based on {@link GatewayTunnelInfo}
   */
  protected _getTunnelAddress(info: GatewayTunnelInfo): string | undefined {
    switch (info.type) {
      case GatewayTunnelInfoType.HTTPS:
      case GatewayTunnelInfoType.HTTP:
        return "//" + info.id + "." + (info.hostname ? info.hostname : window.location.hostname);
      case GatewayTunnelInfoType.TCP:
        return (info.hostname ? info.hostname : window.location.hostname) + ":" + info.assignedPort;
    }
  }

  /* ------------------------------------- */

  /**
   * Internal Lit {@link Task} meant for fetching the list of tunnels.
   * It acts as a reactive controller, and keeps its value cached.
   */
  protected _fetchTunnelsTask = new Task(this, {
    task: async ([realm], { signal }) => {
      return await this._fetchTunnels(realm, signal);
    },
    args: () => [this._realm],
  });

  /**
   * Internal asynchronous function to fetch the list of tunnels.
   */
  protected async _fetchTunnels(
    realm: string = manager.displayRealm,
    _signal?: AbortSignal
  ): Promise<GatewayTunnelInfo[]> {
    const response = await manager.rest.api.GatewayServiceResource.getAllActiveTunnelInfos(realm);
    if (response.status !== 200) {
      throw new Error(response.statusText);
    }

    if (response.data.length > 0) {
      this._updateRefreshTimer(response.data);
      return response.data;
    } else {
      console.warn("No tunnels were received from the manager.");
    }
  }

  /**
   * Sets a timeout to refresh the tunnels whenever the next tunnel is automatically closed.
   */
  protected _updateRefreshTimer(tunnels?: GatewayTunnelInfo[]) {
    if (this._refreshTimer) {
      clearTimeout(this._refreshTimer);
    }

    if (tunnels && tunnels.length > 0) {
      const nextCloseTime = tunnels
        .map((t) => t.autoCloseTime)
        .filter((t) => t)
        .sort()[0];
      if (nextCloseTime) {
        const timeout = nextCloseTime - Date.now();
        if (timeout > 0) {
          this._refreshTimer = window.setTimeout(() => this._fetchTunnelsTask.run(), timeout);
        }
      }
    }
  }

  /* ------------------------------ */

  /**
   * Utility function to fetch the available gateway assets to select from
   */
  protected async _fetchGatewayAssets(requireTunnelSupport = true): Promise<Asset[]> {
    const query = {
      realm: { name: manager.displayRealm },
      select: { attributes: ["tunnelingSupported"] },
      types: this.GATEWAY_TUNNEL_TYPES,
      attributes: requireTunnelSupport
        ? {
            items: [
              {
                name: {
                  predicateType: "string",
                  value: "tunnelingSupported",
                },
                value: {
                  predicateType: "boolean",
                  value: true,
                },
              },
              {
                name: {
                  predicateType: "string",
                  value: "gatewayStatus",
                },
                value: {
                  predicateType: "string",
                  value: "CONNECTED",
                },
              },
            ],
          }
        : undefined,
    } as AssetQuery;
    return (await manager.rest.api.AssetResource.queryAssets(query)).data;
  }
}
