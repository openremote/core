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
import type { GeoJsonConfig } from "@openremote/model";
import manager, { DefaultColor4 } from "@openremote/core";
import {
  type AddLayerObject,
  AttributionControl,
  type IControl,
  type LngLat,
  LngLatBounds,
  type LngLatLike,
  type Map as MapGL,
  type MapOptions,
  type MapMouseEvent,
  Marker,
  type StyleSpecification,
  type GeoJSONSourceSpecification,
} from "maplibre-gl";
import { OrLogoControl } from "./controls/logo";
import { OrMapNavigationControl } from "./controls/navigation";
import { OrMapGeolocateControl } from "./controls/geolocate";
import {
  type ControlPosition,
  OrMapClickedEvent,
  OrMapGeocoderChangeEvent,
  OrMapLongPressEvent,
  type ViewSettings,
} from "./index";
import { OrMapGeocoderControl } from "./controls/geocoder";
import type { OrMapMarker } from "./markers/or-map-marker";
import { getLngLat, metersToPixelsAtMaxZoom } from "./util";
import { isMapboxURL, transformMapboxUrl } from "./util/mapbox-url";
import type { Feature, FeatureCollection } from "geojson";

const maplibreGlStyles = require("maplibre-gl/dist/maplibre-gl.css");

let pkey: string | null;

export class BaseMap {
  protected _map?: MapGL;
  protected _styleParent: Node;
  protected _mapContainer: HTMLElement;
  protected _loaded = false;
  protected _markers: Map<OrMapMarker, Marker> = new Map();
  protected _geoJsonConfig?: GeoJsonConfig;
  protected _geoJsonSources: string[] = [];
  protected _geoJsonLayers: Map<string, any> = new Map();
  protected _viewSettings?: ViewSettings;
  protected _center?: LngLatLike;
  protected _zoom?: number;
  protected _showGeoCodingControl = false;
  protected _showBoundaryBox = false;
  protected _useZoomControls = true;
  protected _showGeoJson = true;
  protected _controls?: (IControl | [IControl, ControlPosition?])[];
  protected _clickHandlers: Map<OrMapMarker, (ev: MouseEvent) => void> = new Map();
  protected _geocoder?: any;

  constructor(
    styleParent: Node,
    mapContainer: HTMLElement,
    showGeoCodingControl = false,
    showBoundaryBox = false,
    useZoomControls = true,
    showGeoJson = true
  ) {
    this._styleParent = styleParent;
    this._mapContainer = mapContainer;
    this._showGeoCodingControl = showGeoCodingControl;
    this._showBoundaryBox = showBoundaryBox;
    this._useZoomControls = useZoomControls;
    this._showGeoJson = showGeoJson;
  }

  public setCenter(center?: LngLatLike): this {
    this._center = getLngLat(center);
    if (this._map && this._center) {
      this._map.setCenter(this._center);
    }
    return this;
  }

  public flyTo(coordinates?: LngLatLike, zoom?: number): this {
    if (!coordinates) {
      coordinates = this._center ? this._center : this._viewSettings ? this._viewSettings.center : undefined;
    }

    if (!zoom) {
      zoom = this._zoom
        ? this._zoom
        : this._viewSettings && this._viewSettings.zoom
          ? this._viewSettings.zoom
          : undefined;
    }

    if (this._map) {
      // Only do flyTo if it has valid LngLat value
      if (coordinates) {
        this._map.flyTo({
          center: coordinates,
          zoom,
        });
      }
    } else {
      this._center = coordinates;
      this._zoom = zoom;
    }

    return this;
  }

  public resize(): this {
    if (this._map) {
      this._map.resize();
    }
    return this;
  }

  public setZoom(zoom?: number): this {
    this._zoom = zoom;
    if (this._map && this._zoom) {
      this._map.setZoom(this._zoom);
    }
    return this;
  }

  public setControls(controls?: (IControl | [IControl, ControlPosition?])[]): this {
    this._controls = controls;
    if (this._map) {
      if (this._controls) {
        this._controls.forEach((control) => {
          if (Array.isArray(control)) {
            const controlAndPosition: [IControl, ControlPosition?] = control;
            this._map!.addControl(controlAndPosition[0], controlAndPosition[1]);
          } else {
            this._map!.addControl(control);
          }
        });
      } else {
        this._map.addControl(new OrMapNavigationControl());
      }
    }
    return this;
  }

  public setGeoJson(geoJsonConfig?: GeoJsonConfig): this {
    this._geoJsonConfig = geoJsonConfig;
    if (this._map) {
      if (this._geoJsonConfig) {
        this._loadGeoJSON(this._geoJsonConfig);
      } else {
        this._loadGeoJSON(this._viewSettings?.geoJson);
      }
    }
    return this;
  }

  public async loadViewSettings() {
    const response = await manager.rest.api.MapResource.getSettings();
    const settings = response.data as any;

    if (settings.override) {
      return settings.override;
    }

    // Load options for current realm or fallback to default if exist
    const realmName = manager.displayRealm || "default";
    this._viewSettings = settings.options
      ? settings.options[realmName]
        ? settings.options[realmName]
        : settings.options.default
      : null;

    if (this._viewSettings) {
      // If Map was already present, so only ran during updates such as realm switches
      if (this._map) {
        this._map.setMinZoom(this._viewSettings.minZoom);
        this._map.setMaxZoom(this._viewSettings.maxZoom);
        if (this._viewSettings.bounds) {
          this._map.setMaxBounds(this._viewSettings.bounds);
        }
        // Unload all GeoJSON that is present, and load new layers if present
        if (this._geoJsonConfig) {
          await this._loadGeoJSON(this._geoJsonConfig);
        } else {
          await this._loadGeoJSON(this._viewSettings?.geoJson);
        }
      }
      if (!this._center) {
        this.setCenter(this._viewSettings.center);
      } else {
        this.setCenter(this._center);
      }
    }

    return settings;
  }

  /**
   * Build the map based on the map config.
   */
  public async build(): Promise<void> {
    if (this._loaded) {
      return;
    }

    // Add style to shadow root
    const style = document.createElement("style");
    style.id = "maplibreGlStyle";
    style.textContent = maplibreGlStyles;
    this._styleParent.appendChild(style);

    const map: typeof import("maplibre-gl") = await import(/* webpackChunkName: "maplibre-gl" */ "maplibre-gl");
    const settings = await this.loadViewSettings();

    const options: MapOptions = {
      attributionControl: false,
      container: this._mapContainer,
      style: settings as StyleSpecification,
      transformRequest: (url, resourceType) => {
        if (!pkey) {
          pkey = new URL(url).searchParams.get("access_token") || "";
        }
        if (isMapboxURL(url)) {
          return transformMapboxUrl(url, pkey, resourceType);
        }
        // Cross-domain tile servers usually have the following headers specified "access-control-allow-methods	GET", "access-control-allow-origin *", "allow GET,HEAD". The "Access-Control-Request-Headers: Authorization" may not be set e.g. with Mapbox tile servers. The CORS preflight request (OPTION) will in this case fail if the "authorization" header is being requested cross-domain. The only headers allowed are so called "simple request" headers, see https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS#simple_requests.
        const headers =
          new URL(window.origin).hostname === new URL(url).hostname
            ? { Authorization: manager.getAuthorizationHeader() }
            : {};
        return {
          headers,
          url,
        };
      },
    };

    if (this._viewSettings) {
      if (this._useZoomControls) {
        options.maxZoom = this._viewSettings.maxZoom;
        options.minZoom = this._viewSettings.minZoom;
      }
      if (this._viewSettings.bounds && !this._showBoundaryBox) {
        options.maxBounds = this._viewSettings.bounds;
      }

      options.boxZoom = this._viewSettings.boxZoom;
      options.zoom = this._viewSettings.zoom;
      options.center = this._viewSettings.center;
    }

    this._center = this._center || (this._viewSettings ? this._viewSettings.center : undefined);
    options.center = this._center;

    if (this._zoom) {
      options.zoom = this._zoom;
    }

    // The worker and the shared module it imports are copied next to the app by its bundler config
    if (!map.getWorkerUrl()) map.setWorkerUrl(new URL("maplibre/maplibre-gl-worker.mjs", document.baseURI).href);

    // Firefox headless mode does not support webgl, see https://bugzilla.mozilla.org/show_bug.cgi?id=1375585
    try {
      this._map = new map.Map(options);
    } catch (error) {
      // Creating the map fails where WebGL2 is unavailable, such as headless Firefox
      if (!(error instanceof map.GPUInitializationError)) throw error;
      console.warn(error.message);
      return;
    }

    // Recreating the context after it was lost and restored can also fail;
    // that failure arrives through the error event since the map already exists.
    // Source: https://maplibre.org/maplibre-gl-js/docs/examples/check-if-webgl-is-supported/
    this._map.on("error", (e) => {
      if (e.error instanceof map.GPUInitializationError) {
        console.warn(e.error.message);
        return;
      }
      console.error(e.error);
    });

    await this._styleLoaded();

    this._map.addControl(new OrLogoControl(), "bottom-left");
    this._map.addControl(new AttributionControl({ compact: true }), "bottom-left");

    this._map.on("click", (e: MapMouseEvent) => {
      this._onMapClick(e.lngLat);
    });

    this._map.on("dblclick", (e: MapMouseEvent) => {
      this._onMapClick(e.lngLat, true);
    });

    if (this._showGeoCodingControl && this._viewSettings && this._viewSettings.geocodeUrl) {
      let bbox: [number, number, number, number] | undefined;
      if (this._viewSettings.bounds) {
        const b = LngLatBounds.convert(this._viewSettings.bounds as any);
        bbox = [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()];
      }
      this._geocoder = new OrMapGeocoderControl(this._viewSettings.geocodeUrl, bbox);
      this._map!.addControl(this._geocoder, "top-right");
    }

    // Add custom controls
    if (this._controls) {
      this._controls.forEach((control) => {
        if (Array.isArray(control)) {
          const controlAndPosition: [IControl, ControlPosition?] = control;
          this._map!.addControl(controlAndPosition[0], controlAndPosition[1]);
        } else {
          this._map!.addControl(control);
        }
      });
    } else {
      this._map.addControl(new OrMapNavigationControl());
      this._map.addControl(new OrMapGeolocateControl());
    }

    // Unload all GeoJSON that is present, and load new layers if present
    if (this._geoJsonConfig) {
      await this._loadGeoJSON(this._geoJsonConfig);
    } else {
      await this._loadGeoJSON(this._viewSettings?.geoJson);
    }

    this._initLongPressEvent();
    this.createBoundaryBox();
  }

  protected _styleLoaded(): Promise<void> {
    return new Promise((resolve) => {
      if (this._map) {
        this._map.once("style.load", () => resolve());
      }
    });
  }

  // Clean up of internal resources associated with the map.
  // Normally used during disconnectedCallback
  public unload() {
    if (this._map) {
      this._map.remove();
      this._map = undefined;
    }
  }

  protected _onMapClick(lngLat: LngLat, doubleClicked: boolean = false) {
    this._mapContainer.dispatchEvent(new OrMapClickedEvent(lngLat, doubleClicked));
  }

  protected async _loadGeoJSON(geoJsonConfig?: GeoJsonConfig) {
    // Remove old layers
    if (this._geoJsonLayers.size > 0) {
      this._geoJsonLayers.forEach((layer, layerId) => this._map!.removeLayer(layerId));
      this._geoJsonLayers = new Map();
    }
    // Remove old sources
    if (this._geoJsonSources.length > 0) {
      this._geoJsonSources.forEach((sourceId) => this._map!.removeSource(sourceId));
      this._geoJsonSources = [];
    }

    // Add new ones if present
    if (this._showGeoJson && geoJsonConfig) {
      // If array of features (most of the GeoJSONs use this)
      if (geoJsonConfig.source.type == "FeatureCollection") {
        const groupedSources = this.groupSourcesByGeometryType(geoJsonConfig.source);
        groupedSources?.forEach((features, type) => {
          const newSource = {
            type: "geojson",
            data: {
              type: "FeatureCollection",
              features,
            },
          } as any as GeoJSONSourceSpecification;
          const sourceInfo = this.addGeoJSONSource(newSource);
          if (sourceInfo) {
            this.addGeoJSONLayer(type, sourceInfo.sourceId);
          }
        });

        // Or only 1 feature is added
      } else if (geoJsonConfig.source.type == "Feature") {
        const sourceInfo = this.addGeoJSONSource(geoJsonConfig.source);
        if (sourceInfo) {
          this.addGeoJSONLayer(sourceInfo.source.type, sourceInfo.sourceId);
        }
      } else {
        console.error("Could not create layer since source type is neither 'FeatureCollection' nor 'Feature'.");
      }
    }
  }

  public groupSourcesByGeometryType(sources: FeatureCollection): Map<string, Feature[]> | undefined {
    const groupedSources: Map<string, Feature[]> = new Map();
    sources.features.forEach((feature) => {
      const sources = groupedSources.get(feature.geometry.type) ?? [];
      sources.push(feature);
      groupedSources.set(feature.geometry.type, sources);
    });
    return groupedSources;
  }

  public addGeoJSONSource(
    source: GeoJSONSourceSpecification
  ): { source: GeoJSONSourceSpecification; sourceId: string } | undefined {
    if (!this._map) {
      console.error("mapGl instance not found!");
      return;
    }
    const id = Date.now() + "-" + (this._geoJsonSources.length + 1);
    this._map.addSource(id, source);
    this._geoJsonSources.push(id);
    return {
      source,
      sourceId: id,
    };
  }

  public addGeoJSONLayer(typeString: string, sourceId: string) {
    if (!this._map) {
      console.error("mapGl instance not found!");
      return;
    }

    const type = typeString as
      "Point" | "MultiPoint" | "LineString" | "MultiLineString" | "Polygon" | "MultiPolygon" | "GeometryCollection";
    const layerId = sourceId + "-" + type;

    // If layer is not added yet
    if (this._geoJsonLayers.get(layerId) == undefined) {
      // Get realm color by getting value from CSS
      let realmColor: string = getComputedStyle(this._mapContainer).getPropertyValue("--or-app-color4");
      if (realmColor == undefined || realmColor.length == 0) {
        realmColor = DefaultColor4;
      }

      const layer = {
        id: layerId,
        source: sourceId,
      } as any;

      // Set styling based on type
      switch (type) {
        case "Point":
        case "MultiPoint": {
          layer.type = "circle";
          layer.paint = {
            "circle-radius": 12,
            "circle-color": realmColor,
          };
          this._geoJsonLayers.set(layerId, layer);
          this._map.addLayer(layer);
          break;
        }
        case "LineString":
        case "MultiLineString": {
          layer.type = "line";
          layer.paint = {
            "line-color": realmColor,
            "line-width": 4,
          };
          this._geoJsonLayers.set(layerId, layer);
          this._map.addLayer(layer);
          break;
        }
        case "Polygon":
        case "MultiPolygon": {
          layer.type = "fill";
          layer.paint = {
            "fill-color": realmColor,
            "fill-opacity": 0.3,
          };
          this._geoJsonLayers.set(layerId, layer);
          this._map.addLayer(layer);

          // Add extra layer with outline
          const outlineId = layerId + "-outline";
          const outlineLayer = {
            id: outlineId,
            source: sourceId,
            type: "line",
            paint: {
              "line-color": realmColor,
              "line-width": 2,
            },
          } as AddLayerObject;
          this._geoJsonLayers.set(outlineId, outlineLayer);
          this._map.addLayer(outlineLayer);
          break;
        }
        case "GeometryCollection": {
          console.error("GeometryCollection GeoJSON is not implemented yet!");
        }
      }
    }
  }

  public addMarker(marker: OrMapMarker) {
    if (marker.hasPosition()) {
      this._updateMarkerElement(marker, true);
    }
  }

  public removeMarker(marker: OrMapMarker) {
    this._removeMarkerRadius(marker);
    this._updateMarkerElement(marker, false);
  }

  public onMarkerChanged(marker: OrMapMarker, prop: string) {
    if (!this._loaded) {
      return;
    }

    switch (prop) {
      case "lat":
      case "lng":
      case "radius":
        if (marker.hasPosition()) {
          if (marker._actualMarkerElement) {
            this._updateMarkerPosition(marker);
          } else {
            this._updateMarkerElement(marker, true);
          }
        } else if (marker._actualMarkerElement) {
          this._updateMarkerElement(marker, false);
        }
        break;
    }
  }

  protected _updateMarkerPosition(marker: OrMapMarker) {
    this._markers.get(marker)?.setLngLat([marker.lng!, marker.lat!]);
    this._createMarkerRadius(marker);
  }

  protected _updateMarkerElement(marker: OrMapMarker, doAdd: boolean) {
    let mGl = this._markers.get(marker);
    if (mGl) {
      marker._actualMarkerElement = undefined;
      this._removeMarkerClickHandler(marker, mGl.getElement());
      mGl.remove();
      this._markers.delete(marker);
    }

    if (doAdd) {
      const elem = marker._createMarkerElement();

      if (elem) {
        mGl = new Marker({
          element: elem,
          anchor: "top-left",
        })
          .setLngLat([marker.lng!, marker.lat!])
          .addTo(this._map!);

        this._markers.set(marker, mGl);

        marker._actualMarkerElement = mGl.getElement() as HTMLDivElement;

        if (marker.interactive) {
          this._addMarkerClickHandler(marker, mGl.getElement());
        }
      }
      if (marker.radius) {
        this._createMarkerRadius(marker);
      }
    }
  }

  protected _removeMarkerRadius(marker: OrMapMarker) {
    if (this._map && this._loaded && marker.radius && marker.lat && marker.lng) {
      if (this._map.getSource("circleData")) {
        this._map.removeLayer("marker-radius-circle");
        this._map.removeSource("circleData");
      }
    }
  }

  protected _createMarkerRadius(marker: OrMapMarker) {
    if (this._map && this._loaded && marker.radius && marker.lat && marker.lng) {
      this._removeMarkerRadius(marker);

      this._map.addSource("circleData", {
        type: "geojson",
        data: {
          type: "FeatureCollection",
          features: [
            {
              type: "Feature",
              geometry: {
                type: "Point",
                coordinates: [marker.lng, marker.lat],
              },
              properties: {},
            },
          ],
        },
      });

      this._map.addLayer({
        id: "marker-radius-circle",
        type: "circle",
        source: "circleData",
        paint: {
          "circle-radius": [
            "interpolate",
            ["exponential", 2],
            ["zoom"],
            0,
            0,
            20,
            metersToPixelsAtMaxZoom(marker.radius, marker.lat),
          ],
          "circle-color": "red",
          "circle-opacity": 0.3,
          "circle-pitch-alignment": "map", // Keeps the circle flat against the map surface
        },
      });
    }
  }

  public createBoundaryBox(boundsArray: string[] = []) {
    if (this._map && this._loaded && this._showBoundaryBox && this._viewSettings?.bounds) {
      if (this._map.getSource("bounds")) {
        this._map.removeLayer("bounds");
        this._map.removeSource("bounds");
      }

      if (boundsArray.length !== 4) {
        boundsArray = this._viewSettings?.bounds.toString().split(",");
      }
      const req = [
        [
          [boundsArray[0], boundsArray[3]],
          [boundsArray[2], boundsArray[3]],
          [boundsArray[2], boundsArray[1]],
          [boundsArray[0], boundsArray[1]],
          [boundsArray[0], boundsArray[3]],
        ],
      ];
      this._map.fitBounds([
        parseFloat(boundsArray[0]) + 0.01,
        parseFloat(boundsArray[1]) - 0.01,
        parseFloat(boundsArray[2]) - 0.01,
        parseFloat(boundsArray[3]) + 0.01,
      ]);
      this._map.addSource("bounds", {
        type: "geojson",
        data: {
          type: "Feature",
          properties: {},
          geometry: {
            type: "Polygon",
            // @ts-ignore
            coordinates: req,
          },
        },
      });

      this._map.addLayer({
        id: "bounds",
        type: "fill",
        source: "bounds",
        paint: {
          "fill-color": "#FF0000",
          "fill-opacity": 0.4,
        },
      });
    }
  }

  protected _addMarkerClickHandler(marker: OrMapMarker, elem: HTMLElement) {
    if (elem) {
      const handler = (ev: MouseEvent) => {
        ev.stopPropagation();
        marker._onClick(ev);
      };
      this._clickHandlers.set(marker, handler);
      elem.addEventListener("click", handler);
    }
  }

  protected _removeMarkerClickHandler(marker: OrMapMarker, elem: HTMLElement) {
    const handler = this._clickHandlers.get(marker);
    if (handler && elem) {
      elem.removeEventListener("click", handler);
      this._clickHandlers.delete(marker);
    }
  }

  protected async _forwardGeocode(config: any) {
    const features = [];
    try {
      const request =
        this._viewSettings!.geocodeUrl +
        "/search?q=" +
        config.query +
        "&format=geojson&polygon_geojson=1&addressdetails=1";
      const response = await fetch(request);
      const geojson = await response.json();
      for (const feature of geojson.features) {
        const center = [
          feature.bbox[0] + (feature.bbox[2] - feature.bbox[0]) / 2,
          feature.bbox[1] + (feature.bbox[3] - feature.bbox[1]) / 2,
        ];
        const point = {
          type: "Feature",
          geometry: {
            type: "Point",
            coordinates: center,
          },
          place_name: feature.properties.display_name,
          properties: feature.properties,
          text: feature.properties.display_name,
          place_type: ["place"],
          center,
        };
        features.push(point);
      }
    } catch (e) {
      console.error(`Failed to forwardGeocode with error: ${e}`);
    }

    return {
      features,
    };
  }

  public async _reverseGeocode(config: { lat: number; lon: number }) {
    const features = [];
    try {
      const request =
        this._viewSettings!.geocodeUrl +
        "/reverse?lat=" +
        config.lat +
        "&lon=" +
        config.lon +
        "&format=geojson&polygon_geojson=1&addressdetails=1";
      const response = await fetch(request);
      const geojson = await response.json();
      for (const feature of geojson.features) {
        const center = [
          feature.bbox[0] + (feature.bbox[2] - feature.bbox[0]) / 2,
          feature.bbox[1] + (feature.bbox[3] - feature.bbox[1]) / 2,
        ];
        const point = {
          type: "Feature",
          geometry: {
            type: "Point",
            coordinates: center,
          },
          place_name: feature.properties.display_name,
          properties: feature.properties,
          text: feature.properties.display_name,
          place_type: ["place"],
          center,
        };
        features.push(point);
      }
    } catch (e) {
      console.error(`Failed to reverseGeocode with error: ${e}`);
    }

    return {
      features,
    };
  }

  protected _initLongPressEvent() {
    if (this._map) {
      let pressTimeout: NodeJS.Timeout | null;
      let pos: LngLat;
      const clearTimeoutFunc = () => {
        if (pressTimeout) clearTimeout(pressTimeout);
        pressTimeout = null;
      };

      this._map.on("touchstart", (e) => {
        if (e.originalEvent.touches.length > 1) {
          return;
        }
        pos = e.lngLat;
        pressTimeout = setTimeout(() => {
          this._onLongPress(pos!);
        }, 500);
      });

      this._map.on("mousedown", (e) => {
        if (!pressTimeout && e.originalEvent.button === 0) {
          pos = e.lngLat;
          pressTimeout = setTimeout(() => {
            this._onLongPress(pos!);
            pressTimeout = null;
          }, 500);
        }
      });

      this._map.on("movestart", clearTimeoutFunc);
      this._map.on("mouseup", clearTimeoutFunc);
      this._map.on("touchend", clearTimeoutFunc);
      this._map.on("touchcancel", clearTimeoutFunc);
      this._map.on("touchmove", clearTimeoutFunc);
      this._map.on("moveend", clearTimeoutFunc);
    }
  }

  protected _onLongPress(lngLat: LngLat) {
    this._mapContainer.dispatchEvent(new OrMapLongPressEvent(lngLat));
  }

  protected _onGeocodeChange(geocode: any) {
    this._mapContainer.dispatchEvent(new OrMapGeocoderChangeEvent(geocode));
  }
}
