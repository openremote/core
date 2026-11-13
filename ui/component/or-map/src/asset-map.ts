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
import {
  type CircleLayerSpecification,
  type GeoJSONFeature,
  type GeoJSONSource,
  type MapSourceDataEvent,
  Marker,
} from "maplibre-gl";
import type { Feature, Point } from "geojson";
import type { AssetWithLocation, ClusterConfig, MapFilter } from "./types";
import { BaseMap } from "./base-map";
import { OrClusterMarker, type Slice } from "./markers/or-cluster-marker";
import { getMarkerIconAndColorFromAssetType } from "./util";
import { OrMapLoadedEvent, OrMapMarkersChangedEvent } from ".";
import { Util } from "@openremote/core";
import { type AssetQuery, type AttributeEvent, WellknownAttributes } from "@openremote/model";
import { OrMapLegendControl, OrMapLegendEvent } from "./controls/legend";
import { OrMapPresetFilterControl, OrMapPresetFilterEvent } from "./controls/preset-filter";

type IdentifiableAsset = AssetWithLocation & { id: string; type: string };

/**
 * This class handles where to display asset markers and clusters on the map.
 *
 * Currently, the standard flow to display assets as markers on the map goes as follows:
 *
 * 1. The map page adds assets to the {@link AssetMap}, which computes assets in view and then dispatches {@link OrMapMarkersChangedEvent}.
 * 2. The map page receives the event, renders the received assets as {@link OrMapMarkerAsset} by including them inside the map element's HTML body.
 * 3. The map component then receives either an event from the {@link OrMapMarkerAsset},
 * or when the map component sees a slotted {@link OrMapMarker} was added. From there the {@link OrMapMarker}s are passed to the {@link BaseMap} to be assigned to a MapLibre marker.
 *
 * @todo consider using `this._map.setGlobalStateProperty` for static cluster property
 */
export class AssetMap extends BaseMap {
  private static _clusterProperty = "assetType";

  // All assets ever added — source of truth for filtering and control counts
  private _allAssets: Record<string, AssetWithLocation> = {};
  // Assets currently rendered in the GeoJSON source (visible subset)
  protected _assets: Record<string, AssetWithLocation | null> = {};
  protected _assetTypeColors: Record<string, string> = {};
  protected _assetsOnScreen: Record<string, AssetWithLocation> = {};
  protected _cachedClusters: Record<string, Marker> = {};
  protected _clustersOnScreen: Record<string, Marker> = {};
  protected _clusterConfig?: ClusterConfig;
  protected _source?: GeoJSONSource;

  // Filter / legend state
  private _hostElement: HTMLElement;
  private _filters?: MapFilter[];
  private _showLegend: boolean;
  private _activeFilter: AssetQuery | null = null;
  private _excludedTypes: string[] = [];
  private _allAssetTypes: string[] = [];
  private _allAssetCounts: Record<string, number> = {};
  private _presetFilterControl?: OrMapPresetFilterControl;
  private _legendControl?: OrMapLegendControl;

  // Stable event handler refs so they can be removed on cleanup
  private _onPresetFilter = (e: Event) => {
    this._activeFilter = (e as OrMapPresetFilterEvent).detail;
    this._applyVisibilityFilters();
  };

  private _onLegendChange = (e: Event) => {
    this._excludedTypes = (e as OrMapLegendEvent).detail;
    this._applyVisibilityFilters();
  };

  protected _onMove = () => this._updateMarkers();
  protected _onMoveEnd = (e: any) => {
    // Ensure marker updates happen after the frame
    requestAnimationFrame(() => {
      // The clicked marker may not be removed,
      // if the camera zoom level is directly between 2 levels
      if (e.marker instanceof OrClusterMarker) {
        this._clustersOnScreen[e.marker._clusterId]?.remove();
      }
      this._updateMarkers();
    });
  };

  protected _onData = (e: MapSourceDataEvent) => {
    if (this._map && e.isSourceLoaded && e.sourceId === "assets") {
      this._map.on("move", this._onMove);
      this._map.on("moveend", this._onMoveEnd);
      this._updateMarkers();
    }
  };

  constructor(
    styleParent: Node,
    mapContainer: HTMLElement,
    hostElement: HTMLElement,
    showGeoCodingControl = false,
    showBoundaryBox = false,
    useZoomControls = true,
    showGeoJson = true,
    clusterConfig?: ClusterConfig,
    filters?: MapFilter[],
    showLegend = true
  ) {
    super(styleParent, mapContainer, showGeoCodingControl, showBoundaryBox, useZoomControls, showGeoJson);
    this._clusterConfig = clusterConfig;
    this._hostElement = hostElement;
    this._filters = filters;
    this._showLegend = showLegend;
  }

  override async build(): Promise<void> {
    return super.build().then(() => {
      this._map?.on("load", async () => await this.load());
    });
  }

  // ── Public asset API (filter-aware) ──────────────────────────────────────

  public addAsset(asset: AssetWithLocation) {
    if (!asset.id) return;
    this._allAssets[asset.id] = asset;
    this._syncAssetMeta();
    if (this._presetFilterControl) this._presetFilterControl.assets = Object.values(this._allAssets);
    this._sourceAddAsset(asset);
  }

  public addAssets(assets: AssetWithLocation[]) {
    for (const asset of assets) if (asset.id) this._allAssets[asset.id] = asset;
    this._syncAssetMeta();
    if (this._presetFilterControl) this._presetFilterControl.assets = Object.values(this._allAssets);
    this._sourceAddAssets(assets);
  }

  public updateAttribute(event: AttributeEvent) {
    const id = event?.ref?.id;
    if (!id) return;

    if (this._allAssets[id]) {
      this._allAssets[id] = Util.updateAsset(structuredClone(this._allAssets[id]), event);
      if (this._presetFilterControl) this._presetFilterControl.assets = Object.values(this._allAssets);
    }

    // Update the GeoJSON source if the asset is currently visible
    if (!this._source || !this._assets[id]) return;

    if (event.value == null && event.ref?.name === WellknownAttributes.LOCATION) {
      this._assets[id] = null;
      this._source.updateData({ remove: [id] });
      return;
    }

    const asset = (this._assets[id] = Util.updateAsset(structuredClone(this._assets[id]), event));
    const newGeometry = asset.attributes.location.value;
    const newProperties = Object.entries(AssetMap._assetToFeature(asset as IdentifiableAsset).properties).map(
      ([key, value]) => ({ key, value })
    );
    this._source.updateData({ update: [{ id, newGeometry, addOrUpdateProperties: newProperties }] });
  }

  public removeAssets(ids: string[]) {
    for (const id of ids) delete this._allAssets[id];
    this._syncAssetMeta();
    if (this._presetFilterControl) this._presetFilterControl.assets = Object.values(this._allAssets);
    for (const id of ids) this._assets[id] = null;
    this._source?.updateData({ remove: ids });
  }

  public removeAllAssets() {
    this._allAssets = {};
    this._allAssetTypes = [];
    this._allAssetCounts = {};
    this._activeFilter = null;
    this._excludedTypes = [];
    this._hostElement.classList.remove("has-legend");
    this._legendControl?.clear();
    this._presetFilterControl?.clear();
    this._assets = {};
    this._source?.updateData({ removeAll: true });
  }

  // ── Dynamic control updates ───────────────────────────────────────────────

  public setFilters(filters: MapFilter[] | undefined) {
    this._filters = filters;
    if (!this._loaded) return;
    if (this._presetFilterControl) {
      this._map?.removeControl(this._presetFilterControl);
      this._presetFilterControl = undefined;
    }
    if (filters?.length) {
      this._presetFilterControl = new OrMapPresetFilterControl(filters, Object.values(this._allAssets));
      this._activeFilter = this._presetFilterControl.getInitialFilter();
      this._map?.addControl(this._presetFilterControl, "top-left");
    } else {
      this._activeFilter = null;
    }
    this._hostElement.classList.toggle("has-filters", Boolean(filters?.length));
    this._applyVisibilityFilters();
  }

  public setShowLegend(showLegend: boolean) {
    this._showLegend = showLegend;
    if (!this._loaded) return;
    if (!showLegend && this._legendControl) {
      this._map?.removeControl(this._legendControl);
      this._legendControl = undefined;
    } else if (showLegend && !this._legendControl) {
      this._legendControl = new OrMapLegendControl(this._allAssetTypes, this._excludedTypes, this._allAssetCounts);
      this._map?.addControl(this._legendControl, "bottom-right");
    }
    this._syncAssetMeta();
  }

  // ── Source-level helpers (operate on _assets / GeoJSON source only) ───────

  private _sourceAddAsset(asset: AssetWithLocation) {
    if (!this._source) return;
    const features = this._map!.querySourceFeatures("assets");
    if (!this._hasRequired(asset) || !this._isAssetVisible(asset) || !this._isMissing(asset, features)) return;
    if (this._addAssetType(asset.type)) this._updateClusterProperties(this._source);
    this._assets[asset.id] = asset;
    this._source.updateData({ add: [AssetMap._assetToFeature(asset)] });
  }

  private _sourceAddAssets(assets: AssetWithLocation[]) {
    if (!this._source) return;
    const features = this._map!.querySourceFeatures("assets");
    let missing = assets.filter(this._hasRequired).filter((a) => this._isAssetVisible(a));
    if (features?.length) missing = missing.filter((a) => this._isMissing(a, features));
    if (!missing.length) return;
    let newTypes = false;
    for (const asset of missing) {
      newTypes = this._addAssetType(asset.type) || newTypes;
      this._assets[asset.id] = asset;
    }
    if (newTypes) this._updateClusterProperties(this._source);
    this._source.updateData({ add: missing.map(AssetMap._assetToFeature) });
  }

  // ── Filter / legend internals ─────────────────────────────────────────────

  private _syncAssetMeta() {
    const counts: Record<string, number> = {};
    for (const id in this._allAssets) {
      const type: string | undefined = this._allAssets[id]?.type;
      if (type !== undefined && type !== "") counts[type] = (counts[type] ?? 0) + 1;
    }
    const types = Object.keys(counts);
    this._allAssetTypes = types;
    this._allAssetCounts = counts;
    this._hostElement.classList.toggle("has-legend", this._showLegend && types.length >= 2);
    this._legendControl?.update(types, counts);
  }

  private _isAssetVisible(asset: AssetWithLocation): boolean {
    if (this._activeFilter && !new Util.AssetQueryHelper(this._activeFilter).matches(asset)) return false;
    if (!asset.type) return true;
    return !this._excludedTypes.includes(asset.type);
  }

  private _applyVisibilityFilters() {
    if (!this._source) return;

    const toRemove: string[] = [];
    const toAdd: IdentifiableAsset[] = [];
    let newTypes = false;

    for (const id in this._assets) {
      if (this._assets[id] && !this._isAssetVisible(this._assets[id]!)) {
        this._assets[id] = null;
        toRemove.push(id);
      }
    }

    for (const id in this._allAssets) {
      const asset = this._allAssets[id];
      if (!asset || !this._hasRequired(asset) || this._assets[id]) continue;
      if (this._isAssetVisible(asset)) {
        const a = asset as IdentifiableAsset;
        newTypes = this._addAssetType(a.type) || newTypes;
        this._assets[a.id] = a;
        toAdd.push(a);
      }
    }

    if (!toRemove.length && !toAdd.length) return;

    if (newTypes) this._updateClusterProperties(this._source);
    this._source.updateData({
      ...(toRemove.length && { remove: toRemove }),
      ...(toAdd.length && { add: toAdd.map(AssetMap._assetToFeature) }),
    });
  }

  public override unload() {
    this._mapContainer.removeEventListener(OrMapPresetFilterEvent.NAME, this._onPresetFilter);
    this._mapContainer.removeEventListener(OrMapLegendEvent.NAME, this._onLegendChange);
    super.unload();
  }

  // ── Map lifecycle ─────────────────────────────────────────────────────────

  private async load() {
    if (!this._map || this._loaded) return;

    if (this._map.getSource("assets")) {
      if (this._map.getLayer("asset")) this._map.removeLayer("asset");
      if (this._map.getLayer("cluster")) this._map.removeLayer("cluster");
      this._map.removeSource("assets");
    }

    const baseLayer: Omit<CircleLayerSpecification, "id"> = {
      type: "circle",
      source: "assets",
      paint: { "circle-radius": 0 },
    };

    this._map
      .addSource("assets", {
        type: "geojson",
        cluster: this._clusterConfig?.cluster ?? true,
        clusterRadius: this._clusterConfig?.clusterRadius ?? 180,
        clusterMaxZoom: this._clusterConfig?.clusterMaxZoom ?? 17,
        clusterProperties: this._getClusterProperties(),
        promoteId: "id", // Promote the id property as feature id
        data: { type: "FeatureCollection", features: [] }, // TODO: consider preloading data to avoid tile cache misses
      })
      .addLayer({ ...baseLayer, id: "cluster", filter: ["has", "point_count"] })
      .addLayer({ ...baseLayer, id: "asset", filter: ["!", ["has", "point_count"]] });

    this._source = this._map.getSource("assets") as GeoJSONSource;

    this._map.on("sourcedata", this._onData);

    // Create asset-specific controls
    if (this._filters?.length) {
      this._presetFilterControl = new OrMapPresetFilterControl(this._filters, Object.values(this._allAssets));
      this._map?.addControl(this._presetFilterControl, "top-left");
    }
    if (this._showLegend) {
      this._legendControl = new OrMapLegendControl(this._allAssetTypes, this._excludedTypes, this._allAssetCounts);
      this._map?.addControl(this._legendControl, "bottom-right");
    }
    this._hostElement.classList.toggle("has-filters", Boolean(this._filters?.length));
    this._hostElement.classList.toggle("has-legend", this._showLegend && this._allAssetTypes.length >= 2);

    // Listen for filter/legend events from the controls (bubble through _mapContainer)
    this._mapContainer.addEventListener(OrMapPresetFilterEvent.NAME, this._onPresetFilter);
    this._mapContainer.addEventListener(OrMapLegendEvent.NAME, this._onLegendChange);

    // Restore the active filter synchronously so the buffer flush below applies it immediately,
    // preventing _applyVisibilityFilters from needing to removeAll + re-add after load.
    if (this._presetFilterControl) this._activeFilter = this._presetFilterControl.getInitialFilter();

    // Flush assets that were added before the source was ready
    const buffered = Object.values(this._allAssets);
    if (buffered.length) this._sourceAddAssets(buffered);

    this._mapContainer.dispatchEvent(new OrMapLoadedEvent());
    this._loaded = true;
  }

  protected _updateMarkers() {
    if (!this._map) return;
    const features = this._map.querySourceFeatures("assets");
    const newAssets = this._updateAssets(features);
    this._updateClusters(features);
    this._mapContainer.dispatchEvent(new OrMapMarkersChangedEvent(newAssets));
  }

  private _updateAssets(features: GeoJSONFeature[]) {
    const newAssets: Record<string, AssetWithLocation> = {};

    for (const feature of features) {
      if (!feature.properties.id) continue;
      const id = feature.properties.id as string;
      if (this._assets[id]) newAssets[id] = this._assets[id];
    }

    for (const id in this._assetsOnScreen) {
      if (!newAssets[id]) delete this._assetsOnScreen[id];
    }

    this._assetsOnScreen = newAssets;
    return Object.values(this._assetsOnScreen);
  }

  private _updateClusters(features: GeoJSONFeature[]) {
    const newClusters: Record<string, Marker> = {};

    // Create missing cluster markers
    for (const feature of features) {
      if (!feature.properties.cluster) continue;
      const id: number = feature.properties.cluster_id;
      let marker = this._cachedClusters[id] ?? this._getClusterMarker(feature);
      // Invalidate the cached cluster marker if the type counts don't match
      // If a new type is added, the marker will have a different cluster_id
      if (marker._element instanceof OrClusterMarker && !marker._element.slicesMatch(feature.properties)) {
        marker.remove();
        marker = this._getClusterMarker(feature).addTo(this._map!);
      }
      newClusters[id] = marker;
      // Add the new cluster marker if it's not already on screen
      if (!this._clustersOnScreen[id]) marker.addTo(this._map!);
    }

    // Cleanup clusters that should no longer be on screen
    for (const id in this._clustersOnScreen) {
      if (!newClusters[id]) this._clustersOnScreen[id]?.remove();
    }
    this._clustersOnScreen = newClusters;
  }

  private _getClusterMarker(feature: GeoJSONFeature) {
    const id: number = feature.properties.cluster_id;
    const geometry = feature.geometry as Point;
    const [lng, lat] = geometry.coordinates;

    const slices: Slice[] = Object.entries(feature.properties)
      .filter(([k]) => k in this._assetTypeColors)
      .map(([type, count]) => [type, this._assetTypeColors[type], count]);

    return (this._cachedClusters[id] = new Marker({
      element: new OrClusterMarker(slices, id, lng, lat, this._map!),
    }).setLngLat([lng, lat]));
  }

  private _hasRequired(asset: AssetWithLocation): asset is IdentifiableAsset {
    return Boolean(asset?.id && asset?.type);
  }

  private _isMissing(asset: IdentifiableAsset, features: Feature[]) {
    return !features?.some((f) => f.properties?.id === asset.id);
  }

  private _getClusterProperties() {
    return Object.fromEntries(Object.keys(this._assetTypeColors).map(AssetMap._getClusterPropertyExpression));
  }

  /**
   * Registers the color of an asset type the map has not seen before
   * @returns Whether the type is new, meaning the cluster properties are out of date
   */
  private _addAssetType(type: string) {
    if (type in this._assetTypeColors) return false;
    this._assetTypeColors[type] = getMarkerIconAndColorFromAssetType(type)?.color as string;
    return true;
  }

  private _updateClusterProperties(source: GeoJSONSource) {
    source.workerOptions.clusterProperties = this._getClusterProperties();
    // Data updates keep the existing cluster index, only a cluster options update rebuilds it with new properties
    const { cluster } = source.getClusterOptions();
    if (cluster) source.setClusterOptions({ cluster });
  }

  private static _getClusterPropertyExpression(value: string) {
    return [value, ["+", ["case", ["==", ["get", AssetMap._clusterProperty], value], 1, 0]]];
  }

  private static _assetToFeature({
    id,
    type,
    name,
    attributes,
  }: IdentifiableAsset): Feature<Point, { id: string; name?: string; [AssetMap._clusterProperty]: string }> {
    return {
      type: "Feature",
      geometry: attributes.location.value,
      properties: { id, name, [AssetMap._clusterProperty]: type },
    };
  }
}
