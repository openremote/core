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
import { css, html, type TemplateResult, unsafeCSS } from "lit";
import { customElement, property, query } from "lit/decorators.js";
import "@openremote/or-asset-viewer";
import {
  type OrAssetViewer,
  OrAssetViewerEditToggleEvent,
  OrAssetViewerRequestEditToggleEvent,
  OrAssetViewerSaveEvent,
  OrAssetViewerLoadUserEvent,
  saveAsset,
  type SaveResult,
  type ViewerConfig,
  OrAssetViewerLoadAlarmEvent,
  OrAssetViewerRequestSaveEvent,
} from "@openremote/or-asset-viewer";
import {
  type AssetTreeConfig,
  type ChangeParentEventDetail,
  type OrAssetTree,
  OrAssetTreeFilter,
  OrAssetTreeAddEvent,
  OrAssetTreeAssetEvent,
  OrAssetTreeChangeParentEvent,
  OrAssetTreeRequestAddEvent,
  OrAssetTreeRequestSelectionEvent,
  OrAssetTreeSelectionEvent,
} from "@openremote/or-asset-tree";
import manager, { DefaultBoxShadow, Util } from "@openremote/core";
import { type AppStateKeyed, Page, type PageProvider, router } from "@openremote/or-app";
import { createSlice, type Store, createSelector } from "@reduxjs/toolkit";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { type Asset, AssetEventCause, WellknownAssets } from "@openremote/model";
import "@openremote/or-json-forms";
import { getAlarmsRoute, getAssetsRoute, getUsersRoute } from "../routes";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { createRef, ref, type Ref } from "lit/directives/ref.js";

export interface PageAssetsConfig {
  viewer?: ViewerConfig;
  tree?: AssetTreeConfig;
}
export interface AssetsState {}
export interface AssetsStateKeyed extends AppStateKeyed {
  assets: AssetsState;
}
const INITIAL_STATE: AssetsState = {};
const pageAssetsSlice = createSlice({
  name: "pageAssets",
  initialState: INITIAL_STATE,
  reducers: {},
});
export const pageAssetsReducer = pageAssetsSlice.reducer;

export const PAGE_ASSETS_CONFIG_DEFAULT: PageAssetsConfig = {
  tree: {
    add: {
      typesParent: {
        default: {
          exclude: [
            WellknownAssets.TRADFRILIGHTASSET,
            WellknownAssets.TRADFRIPLUGASSET,
            WellknownAssets.ARTNETLIGHTASSET,
            WellknownAssets.CONSOLEASSET,
          ],
        },
      },
    },
  },
};
export function pageAssetsProvider(
  store: Store<AssetsStateKeyed>,
  config?: PageAssetsConfig
): PageProvider<AssetsStateKeyed> {
  return {
    name: "assets",
    routes: ["assets", "assets/:editMode", "assets/:editMode/:ids"],
    pageCreator: () => {
      const page = new PageAssets(store);
      if (config) {
        page.config = config;
      }
      return page;
    },
  };
}

@customElement("page-assets")
export class PageAssets extends Page<AssetsStateKeyed> {
  static get styles() {
    // language=CSS
    return css`
      or-asset-tree {
        align-items: stretch;
        z-index: 1;
      }

      .hideMobile {
        display: none;
      }

      or-asset-viewer {
        align-items: stretch;
        z-index: 0;
      }

      .multipleAssetsView {
        display: flex;
        justify-content: center;
        align-items: center;
        text-align: center;
        height: 100%;
        width: 100%;
      }

      .multipleAssetsView > div {
        display: flex;
        flex-direction: column;
      }

      .multipleAssetsView > div > *:first-child {
        margin: 30px;
      }

      @media only screen and (min-width: 768px) {
        or-asset-tree {
          width: 300px;
          min-width: 300px;
          box-shadow: ${unsafeCSS(DefaultBoxShadow)};
        }

        .hideMobile {
          display: flex;
        }

        or-asset-viewer,
        or-asset-viewer.hideMobile {
          display: initial;
          max-width: calc(100vw - 300px);
        }
      }
    `;
  }

  @property()
  public config?: PageAssetsConfig;

  @property()
  protected _editMode: boolean = false;

  @property() // selected asset ids
  protected _assetIds?: string[];

  @query("#tree")
  protected _tree!: OrAssetTree;

  @query("#viewer")
  protected _viewer?: OrAssetViewer;

  protected _addCallback?: (asset: Asset) => void;
  protected _realmSelector = (state: AssetsStateKeyed) => state.app.realm || manager.displayRealm;

  get name(): string {
    return "assets";
  }

  protected getRealmState = createSelector([this._realmSelector], async (realm: string) => {
    // Prevent execution on initial load
    if (this.isUpdatePending) {
      return;
    }
    if (this._viewer) {
      this._viewer.assetId = undefined;
    }
    if (this._tree) {
      this._tree.refresh();
    }
    this._updateRoute(true);
  });

  constructor(store: Store<AssetsStateKeyed>) {
    super(store);
    this.addEventListener(OrAssetTreeRequestSelectionEvent.NAME, this._onAssetSelectionRequested);
    this.addEventListener(OrAssetTreeSelectionEvent.NAME, (ev) =>
      this._onAssetSelectionChanged(
        false,
        ev.detail.newNodes.map((node) => node.asset.id!)
      )
    );
    this.addEventListener(OrAssetViewerRequestEditToggleEvent.NAME, this._onEditToggleRequested);
    this.addEventListener(OrAssetViewerEditToggleEvent.NAME, this._onEditToggle);
    this.addEventListener(OrAssetTreeRequestAddEvent.NAME, this._onAssetAddRequested);
    this.addEventListener(OrAssetTreeAddEvent.NAME, (ev) => this._onAssetAdd(ev.detail.asset));
    this.addEventListener(OrAssetViewerRequestSaveEvent.NAME, this._onAssetViewerSaveRequested);
    this.addEventListener(OrAssetTreeAssetEvent.NAME, this._onAssetTreeAssetEvent);
    this.addEventListener(OrAssetTreeChangeParentEvent.NAME, (ev) => this._onAssetParentChange(ev.detail));
    this.addEventListener(OrAssetViewerLoadUserEvent.NAME, this._onLoadUserEvent);
    this.addEventListener(OrAssetViewerLoadAlarmEvent.NAME, (ev) => this._onLoadAlarmEvent(ev));
  }

  protected render(): TemplateResult | void {
    let viewerHTML: TemplateResult;
    const multiSelection = this._assetIds && this._assetIds.length > 1;

    if (multiSelection) {
      viewerHTML = html`
        <div class="multipleAssetsView hideMobile">
          <div>
            <or-translate value="multiAssetSelected" .options="${{ assetNbr: this._assetIds.length }}"></or-translate>
            <or-vaadin-button @click=${() => this._onParentChangeClick()}>
              <or-translate value="changeParent"></or-translate>
            </or-vaadin-button>
          </div>
        </div>
      `;
    } else {
      const assetId = this._assetIds && this._assetIds.length === 1 ? this._assetIds[0] : undefined;
      viewerHTML = html`
        <or-asset-viewer
          id="viewer"
          .config="${this.config && this.config.viewer ? this.config.viewer : undefined}"
          class="${!assetId ? "hideMobile" : ""}"
          .assetId="${assetId}"
          .editMode="${this._editMode}"
        ></or-asset-viewer>
      `;
    }

    return html`
      <or-asset-tree
        id="tree"
        .config="${this.config && this.config.tree ? this.config.tree : PAGE_ASSETS_CONFIG_DEFAULT.tree}"
        class="${this._assetIds && this._assetIds.length === 1 ? "hideMobile" : ""}"
        .selectedIds="${this._assetIds}"
      ></or-asset-tree>
      ${viewerHTML}
    `;
  }

  // State is only utilised for initial loading, and for changes within the store.
  // On the assets page, we shouldn't change editMode nor assetIds if the URL/state hasn't changed.
  stateChanged(state: AssetsStateKeyed) {
    this.getRealmState(state); // Order is important here!
    this._editMode = !!(state.app.params && state.app.params.editMode === "true");
    if (state.app.params?.ids) {
      const newIds = state.app.params.ids.split(",");
      if (!Util.objectsEqual(this._assetIds, newIds, true)) {
        this._assetIds = newIds;
        if (this._assetIds.length === 1) {
          this.updateComplete.finally(() => this._tree?.applyFilter(new OrAssetTreeFilter(this._assetIds[0]), true));
        }
      }
    } else {
      this._assetIds = undefined;
    }
  }

  protected _onParentChangeClick() {
    let dialog: OrVaadinDialog | undefined;
    const assetTreeRef: Ref<OrAssetTree> = createRef();

    const blockEvent = (ev: Event) => ev.stopPropagation();

    const setParent = () => {
      const assetTree = assetTreeRef.value!;
      const idd = assetTree.selectedIds!.length === 1 ? assetTree.selectedIds![0] : undefined;
      this._onAssetParentChange({ parentId: idd, assetIds: this._assetIds });
    };

    const clearParent = () => {
      this._onAssetParentChange({ parentId: undefined, assetIds: this._assetIds });
      dialog?.close();
    };

    const onCancel = () => dialog?.close();
    const onOk = () => {
      setParent();
      dialog?.close();
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="setParent"></or-translate>
          </h2>
          <or-asset-tree
            ${ref(assetTreeRef)}
            disableSubscribe
            readonly
            .selectedIds="${[]}"
            style="width: 100%; aspect-ratio: 1/1.75;"
            @or-asset-tree-request-selection="${blockEvent}"
            @or-asset-tree-selection="${blockEvent}"
          ></or-asset-tree>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${onCancel}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <div>
              <or-vaadin-button @click=${clearParent}>
                <or-translate value="clear"></or-translate>
              </or-vaadin-button>
              <or-vaadin-button theme="primary" @click=${onOk}>
                <or-translate value="ok"></or-translate>
              </or-vaadin-button>
            </div>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected _onAssetSelectionRequested(event: OrAssetTreeRequestSelectionEvent) {
    const isModified = this._viewer && this._viewer.isModified();

    if (!isModified) {
      return;
    }

    // Prevent the request and check if user wants to lose changes
    event.detail.allow = false;

    this._confirmContinue(() => {
      this._onAssetSelectionChanged(
        true,
        event.detail.detail.newNodes?.map((node) => node.asset.id!)
      );
    });
  }

  // This is where we set the asset loaded in the asset viewer
  protected _onAssetSelectionChanged(userInitiated: boolean, assetIds: string[] | undefined) {
    if (Util.objectsEqual(this._assetIds, assetIds)) {
      if (!userInitiated) {
        // Asset name or parent has changed and we don't need to react to it here
        return;
      }
      // User has clicked the same node(s)
      if (assetIds.length === 1) {
        // force refresh the selected asset
        if (this._viewer) {
          this._viewer.assetId = undefined;
          setTimeout(() => {
            if (this._viewer) {
              this._viewer.assetId = assetIds[0];
            }
          }, 0);
        }
      }
    } else {
      this._assetIds = assetIds;
      if (this._viewer) {
        console.debug(
          `Asset viewer is now showing asset ${assetIds && assetIds.length === 1 ? assetIds[0] : "undefined"}`
        );
        this._viewer.assetId = assetIds && assetIds.length === 1 ? assetIds[0] : undefined;
      }
      this._updateRoute(false);
    }
  }

  protected _onEditToggleRequested(event: OrAssetViewerRequestEditToggleEvent) {
    // Block the request if current asset is modified then show dialog then navigate
    const isModified = this._viewer.isModified();

    if (!isModified) {
      return;
    }

    event.detail.allow = false;

    this._confirmContinue(() => {
      this._editMode = event.detail.detail;
      this._updateRoute(false);
      if (this._assetIds?.length === 1) {
        // force refresh the selected asset
        if (this._viewer) {
          this._viewer.assetId = undefined;
          setTimeout(() => {
            if (this._viewer) {
              this._viewer.assetId = this._assetIds[0];
            }
          }, 0);
        }
      }
    });
  }

  protected _onEditToggle(event: OrAssetViewerEditToggleEvent) {
    this._editMode = event.detail;
    this._updateRoute(false);
  }

  protected _confirmContinue(action: () => void) {
    if (this._viewer.isModified()) {
      showConfirmDialog(
        this.shadowRoot,
        html`
          <or-vaadin-confirm-dialog @confirm=${() => action()}>
            ${getConfirmDialogContent("error", "loseChanges", "confirmContinueAssetModified", "discard", "cancel")}
          </or-vaadin-confirm-dialog>
        `
      );
    } else {
      action();
    }
  }

  protected _onAssetAddRequested(ev: OrAssetTreeRequestAddEvent) {
    const isModified = this._viewer && this._viewer.isModified();

    if (!isModified) {
      return;
    }

    // Prevent the request and check if user wants to lose changes
    ev.detail.allow = false;

    this._confirmContinue(() => {
      this._onAssetAdd(ev.detail.detail.asset);
    });
  }

  protected async _onAssetAdd(asset: Asset) {
    if (this._editMode) {
      // Just load the asset into the viewer
      this._viewer.asset = asset;
    } else {
      // Save and load
      this._doSaveAndLoad(asset);
    }
  }

  protected _onAssetViewerSaveRequested(ev: OrAssetViewerRequestSaveEvent) {
    const isModified = this._viewer && this._viewer.isModified();

    if (!isModified) {
      return;
    }

    if (this._viewer?.asset && !this._viewer.asset.id) {
      // Prevent the request and handle at this level to allow selection within the tree
      ev.detail.allow = false;
      this._doSaveAndLoad(this._viewer.asset);
    }
  }

  protected async _doSaveAndLoad(asset: Asset) {
    let saveResult: SaveResult;
    const assetTreeAssets: Asset[] = [];

    this._addCallback = (assetTreeCreatedAsset) => {
      if (saveResult) {
        // Save has completed before asset tree node created
        completedCallback();
      } else {
        // Store as it might be asset we are trying to save
        assetTreeAssets.push(assetTreeCreatedAsset);
      }
    };

    const completedCallback = () => {
      this._onAssetSelectionChanged(true, saveResult.asset?.id ? [saveResult.asset.id] : undefined);
      this._viewer.dispatchEvent(new OrAssetViewerSaveEvent(saveResult));
    };

    saveResult = await saveAsset(asset);
    if (saveResult.success) {
      const assetTreeCreatedNode = assetTreeAssets.find((a) => a.id === saveResult.asset.id);
      if (assetTreeCreatedNode) {
        // Asset tree node creation completed before save callback executed
        completedCallback();
      }
    } else {
      this._addCallback = undefined;
    }
  }

  protected async _onAssetParentChange(parentChange: ChangeParentEventDetail) {
    const parentId: string | undefined = parentChange.parentId;
    const assetsIds: string[] = parentChange.assetIds;

    try {
      if (parentId) {
        if (!assetsIds.includes(parentId)) {
          await manager.rest.api.AssetResource.updateParent(parentId, { assetIds: assetsIds });
        } else {
          showSnackbar(undefined, "moveAssetFailed", "dismiss");
        }
      } else {
        // So need to remove parent from all the selected assets
        await manager.rest.api.AssetResource.updateNoneParent({ assetIds: assetsIds });
      }
    } catch (e) {
      showSnackbar(undefined, "moveAssetFailed", "dismiss");
    }
  }

  protected _onAssetTreeAssetEvent(ev: OrAssetTreeAssetEvent) {
    // If new asset created and add callback defined then we pass to there for handling
    if (ev.detail.cause === AssetEventCause.CREATE && this._addCallback) {
      this._addCallback(ev.detail.asset);
    }
  }

  protected _updateRoute(silent: boolean = true) {
    const assetIds = this._assetIds ? this._assetIds.toString() : undefined;
    router.navigate(getAssetsRoute(this._editMode, assetIds), {
      callHooks: !silent,
      callHandler: !silent,
    });
  }

  protected _onLoadUserEvent(event: OrAssetViewerLoadUserEvent, silent: boolean = false) {
    router.navigate(getUsersRoute(event.detail), {
      callHooks: !silent,
      callHandler: !silent,
    });
  }

  protected _onLoadAlarmEvent(event: OrAssetViewerLoadAlarmEvent, silent: boolean = false) {
    router.navigate(getAlarmsRoute(event.detail.toString()), {
      callHooks: !silent,
      callHandler: !silent,
    });
  }
}
