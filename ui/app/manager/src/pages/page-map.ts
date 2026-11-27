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
import { css, html } from "lit";
import { customElement, property, query, state } from "lit/decorators.js";
import { createSelector, createSlice, type Store, type PayloadAction } from "@reduxjs/toolkit";
import "@openremote/or-map";
import {
  type MapAssetCardConfig,
  type OrMap,
  OrMapAssetCardLoadAssetEvent,
  OrMapClickedEvent,
  type OrMapMarkerAsset,
  OrMapMarkerClickedEvent,
  type OrMapGeocoderChangeEvent,
  type MapMarkerAssetConfig,
  OrMapMarkersChangedEvent,
} from "@openremote/or-map";
import manager, { Util } from "@openremote/core";
import {
  type Asset,
  type AssetEvent,
  AssetEventCause,
  type AssetQuery,
  type AttributeEvent,
  type GeoJSONPoint,
  WellknownAttributes,
  WellknownMetaItems,
} from "@openremote/model";
import { getAssetsRoute, getMapRoute } from "../routes";
import { type AppStateKeyed, Page, type PageProvider, router } from "@openremote/or-app";
import type { GenericAxiosResponse } from "@openremote/rest";
import { type ClusterConfig, type MapFilter, Util as MapUtil, type AssetWithLocation } from "@openremote/or-map";

export interface MapState {
  locatedAssets: AssetWithLocation[];
  unlocatedAssets: Asset[];
  currentAssetId: string;
}

export interface MapStateKeyed extends AppStateKeyed {
  map: MapState;
}

const INITIAL_STATE: MapState = {
  locatedAssets: [],
  unlocatedAssets: [],
  currentAssetId: undefined,
};

const pageMapSlice = createSlice({
  name: "pageMap",
  initialState: INITIAL_STATE,
  reducers: {
    assetEventReceived(state: MapState, action: PayloadAction<AssetEvent>) {
      if (action.payload.cause === AssetEventCause.CREATE) {
        if (MapUtil.isAssetWithLocation(action.payload.asset)) {
          state.locatedAssets.push(action.payload.asset);
        } else {
          state.unlocatedAssets.push(action.payload.asset);
        }
      } else if (action.payload.cause === AssetEventCause.DELETE) {
        const index = state.locatedAssets.findIndex((asset) => asset.id === action.payload.asset.id);
        if (index > -1) state.locatedAssets.splice(index, 1);
        else {
          const index = state.unlocatedAssets.findIndex((asset) => asset.id === action.payload.asset.id);
          if (index > -1) state.unlocatedAssets.splice(index, 1);
        }
      }
      return state;
    },
    attributeEventReceived(state: MapState, action: PayloadAction<[string[], AttributeEvent]>) {
      const attrsOfInterest = action.payload[0];
      const attrEvent = action.payload[1];
      const attrName = attrEvent.ref.name;

      // Only react if attribute is an attribute of interest
      if (!attrsOfInterest.includes(attrName)) {
        return;
      }

      const locatedAssets = state.locatedAssets;
      const unlocatedAssets = state.unlocatedAssets;
      const assetId = attrEvent.ref.id;

      const located = locatedAssets.findIndex((a) => a.id === assetId);
      if (located > -1) {
        const asset = Util.updateAsset({ ...locatedAssets[located] }, attrEvent);
        if (MapUtil.isAssetWithLocation(asset)) {
          locatedAssets[located] = asset;
        } else {
          locatedAssets.splice(located, 1);
          unlocatedAssets.push(asset);
        }
      } else {
        const unlocated = unlocatedAssets.findIndex((a) => a.id === assetId);
        if (unlocated > -1) {
          const asset = Util.updateAsset({ ...unlocatedAssets[unlocated] }, attrEvent);
          if (MapUtil.isAssetWithLocation(asset)) {
            unlocatedAssets.splice(unlocated, 1);
            locatedAssets.push(asset);
          } else {
            unlocatedAssets[unlocated] = asset;
          }
        }
      }

      return state;
    },
    setAssets(state, action: PayloadAction<Asset[]>) {
      const locatedAssets = [];
      const unlocatedAssets = [];
      for (const asset of action.payload) {
        if (MapUtil.isAssetWithLocation(asset)) {
          locatedAssets.push(asset);
        } else {
          unlocatedAssets.push(asset);
        }
      }
      return { ...state, locatedAssets, unlocatedAssets };
    },
  },
});

const { assetEventReceived, attributeEventReceived, setAssets } = pageMapSlice.actions;
export const pageMapReducer = pageMapSlice.reducer;

export interface PageMapConfig {
  legend?: {
    show: boolean;
  };
  clustering?: ClusterConfig;
  card?: MapAssetCardConfig;
  assetQuery?: AssetQuery;
  markers?: MapMarkerAssetConfig;
  filters?: MapFilter[];
}

export function pageMapProvider(store: Store<MapStateKeyed>, config?: PageMapConfig): PageProvider<MapStateKeyed> {
  return {
    name: "map",
    routes: ["map", "map/:id"],
    pageCreator: () => {
      const page = new PageMap(store);
      page.config = config || {};
      return page;
    },
  };
}

@customElement("page-map")
export class PageMap extends Page<MapStateKeyed> {
  static get styles() {
    // language=CSS
    return css`
      :host {
        display: flex;
      }

      or-map-asset-card {
        height: 35vh;
        position: absolute;
        bottom: 0;
        right: 0;
        width: 100vw;
        z-index: 3;
      }

      or-map {
        flex: 1 1 auto;
      }

      @media only screen and (min-width: 40em) {
        or-map-asset-card {
          position: absolute;
          top: var(--card-top, 56px);
          left: 10px;
          width: 320px;
          margin: 0;
          height: 400px; /* fallback for IE */
          height: max-content;
          max-height: calc(100vh - 150px);
          z-index: 2;
        }
      }
    `;
  }

  @property()
  public config?: PageMapConfig;

  @query("#map")
  protected _map?: OrMap;

  @state()
  protected _assets: AssetWithLocation[] = [];

  @state()
  protected _currentAsset?: Asset;

  @state()
  protected _assetsOnScreen: AssetWithLocation[] = [];

  protected _locatedAssetSelector = (state: MapStateKeyed) => state.map.locatedAssets;
  protected _unlocatedAssetSelector = (state: MapStateKeyed) => state.map.unlocatedAssets;
  protected _paramsSelector = (state: MapStateKeyed) => state.app.params;
  protected _realmSelector = (state: MapStateKeyed) => state.app.realm || manager.displayRealm;
  protected _realm?: string;

  protected _activeFiltersSelector = createSelector([this._realmSelector], (realm) =>
    this.config?.filters?.filter((f) => !f.realms?.length || f.realms.includes(realm))
  );

  protected _assetSubscriptionId: string;
  protected _attributeSubscriptionId: string;

  protected getAttributesOfInterest(): (string | WellknownAttributes)[] {
    // Extract all label attributes configured in marker config
    let markerLabelAttributes = [];

    if (this.config && this.config.markers) {
      markerLabelAttributes = Object.values(this.config.markers)
        .filter((assetTypeMarkerConfig) => assetTypeMarkerConfig.attributeName)
        .map((assetTypeMarkerConfig) => assetTypeMarkerConfig.attributeName);
    }

    const filterAttributes = (this.config?.filters ?? []).flatMap((f) =>
      new Util.AssetQueryHelper(f.query).collectAttributeNames()
    );

    return [...markerLabelAttributes, ...filterAttributes, WellknownAttributes.LOCATION, WellknownAttributes.DIRECTION];
  }

  protected subscribeAssets = async (realm: string) => {
    let response: GenericAxiosResponse<Asset[]>;
    const attrsOfInterest = this.getAttributesOfInterest();
    const assetQuery: AssetQuery =
      this.config && this.config.assetQuery
        ? this.config.assetQuery
        : {
            realm: {
              name: realm,
            },
            select: {
              attributes: attrsOfInterest,
            },
            attributes: {
              items: [
                {
                  name: {
                    predicateType: "string",
                    value: WellknownAttributes.LOCATION,
                  },
                  meta: [
                    {
                      name: {
                        predicateType: "string",
                        value: WellknownMetaItems.SHOWONDASHBOARD,
                      },
                      negated: true,
                    },
                    {
                      name: {
                        predicateType: "string",
                        value: WellknownMetaItems.SHOWONDASHBOARD,
                      },
                      value: {
                        predicateType: "boolean",
                        value: true,
                      },
                    },
                  ],
                },
              ],
            },
          };

    try {
      response = await manager.rest.api.AssetResource.queryAssets(assetQuery);

      if (!this.isConnected || realm !== this._realmSelector(this.getState())) {
        // No longer connected or realm has changed
        return;
      }

      if (response.data) {
        const assets = response.data;

        this._store.dispatch(setAssets(assets));
        this._map?.addAssets(this._assets);

        const assetSubscriptionId = await manager.events.subscribeAssetEvents(undefined, false, (event) => {
          this._store.dispatch(assetEventReceived(event));
          switch (event.cause) {
            case "DELETE":
              this._map?.removeAssets([event.asset.id]);
              break;
            case "CREATE":
              if (MapUtil.isAssetWithLocation(event.asset)) {
                this._map?.addAsset(event.asset);
              }
              break;
          }
        });

        if (!this.isConnected || realm !== this._realmSelector(this.getState())) {
          manager.events.unsubscribe(assetSubscriptionId);
          return;
        }

        this._assetSubscriptionId = assetSubscriptionId;

        const attributeSubscriptionId = await manager.events.subscribeAttributeEvents(undefined, false, (event) => {
          const interested = attrsOfInterest.includes(event.ref.name);
          let asset: Asset | undefined;
          if (interested) {
            const assets = this._unlocatedAssetSelector(this.getState());
            asset = assets.find((asset) => asset.id === event.ref.id);
            // Update the attribute if the asset already has a location
            // assuming the asset is in the located asset state
            if (!asset) {
              this._map?.updateAttribute(event);
            }
          }
          this._store.dispatch(attributeEventReceived([attrsOfInterest, event]));
          // Add the asset after map state has been updated
          if (interested && asset) {
            const located = this._assets.find((a) => a.id === event.ref.id);
            if (located) {
              this._map?.addAsset(located);
            }
          }
        });

        if (!this.isConnected || realm !== this._realmSelector(this.getState())) {
          this._assetSubscriptionId = undefined;
          manager.events.unsubscribe(assetSubscriptionId);
          manager.events.unsubscribe(attributeSubscriptionId);
          return;
        }

        this._attributeSubscriptionId = attributeSubscriptionId;
      }
    } catch (e) {
      console.error("Failed to subscribe to assets", e);
    }
  };

  protected unsubscribeAssets = () => {
    if (this._assetSubscriptionId) {
      manager.events.unsubscribe(this._assetSubscriptionId);
      this._assetSubscriptionId = undefined;
    }
    if (this._attributeSubscriptionId) {
      manager.events.unsubscribe(this._attributeSubscriptionId);
      this._attributeSubscriptionId = undefined;
    }
    this._map?.removeAllAssets();
  };

  protected _getCurrentAsset = createSelector([this._locatedAssetSelector, this._paramsSelector], (assets, params) => {
    const currentId = params ? params.id : undefined;

    if (!currentId) {
      return null;
    }

    return assets.find((asset) => asset.id === currentId);
  });

  protected _setCenter(geocode: any) {
    this._map!.center = [geocode.geometry.coordinates[0], geocode.geometry.coordinates[1]];
  }

  get name(): string {
    return "map";
  }

  constructor(store: Store<MapStateKeyed>) {
    super(store);
    this.addEventListener(OrMapAssetCardLoadAssetEvent.NAME, this._onLoadAssetEvent);
  }

  protected render() {
    const filters = this._activeFiltersSelector(this.getState());
    this.style.setProperty("--card-top", filters?.length ? "56px" : "10px");

    return html`
      ${this._currentAsset ? html`<or-map-asset-card .config="${this.config?.card}" .assetId="${this._currentAsset.id}" .markerconfig="${this.config?.markers}"></or-map-asset-card>` : ``}

      <or-map
        id="map"
        class="or-map"
        .cluster="${this.config.clustering}"
        .filters="${filters}"
        ?showLegend="${this.config?.legend?.show !== false}"
        showGeoCodingControl
        @or-map-geocoder-change="${(ev: OrMapGeocoderChangeEvent) => {
          this._setCenter(ev.detail.geocode);
        }}"
      >
        ${this._assetsOnScreen
          .sort((a, b) => {
            const pointA = a.attributes[WellknownAttributes.LOCATION].value as GeoJSONPoint;
            const pointB = b.attributes[WellknownAttributes.LOCATION].value as GeoJSONPoint;
            if (pointA && pointB) {
              return pointB.coordinates[1] - pointA.coordinates[1];
            }
          })
          .map(
            (asset) => html`
              <or-map-marker-asset
                ?active="${this._currentAsset && this._currentAsset.id === asset.id}"
                .asset="${asset}"
                .config="${this.config.markers}"
              ></or-map-marker-asset>
            `
          )}
      </or-map>
    `;
  }

  public connectedCallback() {
    super.connectedCallback();
    this.addEventListener(OrMapMarkerClickedEvent.NAME, this._onMapMarkerClick);
    this.addEventListener(OrMapClickedEvent.NAME, this._onMapClick);
    this.addEventListener(OrMapMarkersChangedEvent.NAME, this._onMapMarkersChanged);
  }

  public disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener(OrMapMarkerClickedEvent.NAME, this._onMapMarkerClick);
    this.removeEventListener(OrMapClickedEvent.NAME, this._onMapClick);
    this.removeEventListener(OrMapMarkersChangedEvent.NAME, this._onMapMarkersChanged);
    this.unsubscribeAssets();
  }

  stateChanged(state: MapStateKeyed) {
    this._assets = this._locatedAssetSelector(state);
    this._currentAsset = this._getCurrentAsset(state);
    const realm = this._realmSelector(state);
    if (realm !== this._realm) {
      this._realm = realm;
      this.unsubscribeAssets();
      this.subscribeAssets(realm);
      this._map?.refresh();
    }
  }

  protected _onMapMarkerClick(e: OrMapMarkerClickedEvent) {
    const asset = (e.detail.marker as OrMapMarkerAsset).asset;
    router.navigate(getMapRoute(asset.id));
  }

  protected _onMapClick(e: OrMapClickedEvent) {
    router.navigate(getMapRoute());
  }

  protected _onMapMarkersChanged(e: OrMapMarkersChangedEvent) {
    this._assetsOnScreen = e.detail;
  }

  protected _onLoadAssetEvent(loadAssetEvent: OrMapAssetCardLoadAssetEvent) {
    router.navigate(getAssetsRoute(false, loadAssetEvent.detail));
  }
}
