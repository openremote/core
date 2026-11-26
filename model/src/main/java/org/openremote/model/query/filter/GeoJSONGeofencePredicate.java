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
package org.openremote.model.query.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.locationtech.jts.geom.*;
import org.openremote.model.geo.*;
import org.openremote.model.util.JSONSchemaUtil.JsonSchemaDescription;
import org.openremote.model.util.JSONSchemaUtil.JsonSchemaTitle;
import org.openremote.model.util.ValueUtil;

/**
 * Predicate for GEO JSON point values; will return true if the point is within the specified
 * GeoJSON geometry (Polygon, MultiPolygon, or Feature/FeatureCollection containing such geometries)
 * unless negated. This allows for complex geofence shapes such as country borders.
 */
@JsonSchemaTitle("GeoJSON Geofence")
@JsonSchemaDescription(
    "Predicate for GeoJSON point values; will return true if the point is within the specified GeoJSON geometry (Polygon, MultiPolygon, or Feature/FeatureCollection containing such geometries) unless negated.")
public class GeoJSONGeofencePredicate extends GeofencePredicate {

  private static final Logger LOG = Logger.getLogger(GeoJSONGeofencePredicate.class.getName());
  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  public static final String name = "geojson";

  @JsonProperty public String geoJSON;

  // Cached geometry for performance
  private transient Geometry cachedGeometry;

  public GeoJSONGeofencePredicate() {}

  @JsonCreator
  public GeoJSONGeofencePredicate(
      @JsonProperty("geoJSON") String geoJSON, @JsonProperty("negated") boolean negated) {
    this.geoJSON = geoJSON;
    this.negated = negated;
  }

  public GeoJSONGeofencePredicate(@JsonProperty("geoJSON") String geoJSON) {
    this(geoJSON, false);
  }

  @Override
  public GeoJSONGeofencePredicate negate() {
    negated = !negated;
    return this;
  }

  public GeoJSONGeofencePredicate geoJSON(String geoJSON) {
    this.geoJSON = geoJSON;
    this.cachedGeometry = null; // Invalidate cache
    return this;
  }

  public String getGeoJSON() {
    return geoJSON;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GeoJSONGeofencePredicate that = (GeoJSONGeofencePredicate) o;
    return negated == that.negated && Objects.equals(geoJSON, that.geoJSON);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, negated, geoJSON);
  }

  @Override
  public double[] getCentrePoint() {
    try {
      Geometry geometry = parseGeometry();
      if (geometry != null) {
        Point centroid = geometry.getCentroid();
        return new double[] {centroid.getX(), centroid.getY()};
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to get center point from GeoJSON", e);
    }
    return new double[] {0, 0};
  }

  /** Parse the GeoJSON string into a JTS Geometry using the GeoJSON model objects. */
  private Geometry parseGeometry() {
    if (cachedGeometry != null) {
      return cachedGeometry;
    }

    if (geoJSON == null || geoJSON.trim().isEmpty()) {
      LOG.warning("GeoJSON is null or empty");
      return null;
    }

    try {
      // Try to deserialize as GeoJSON (handles Feature, FeatureCollection, or Geometry)
      GeoJSON geoJSONObject = ValueUtil.parse(geoJSON, GeoJSON.class).orElse(null);

      if (geoJSONObject == null) {
        LOG.warning("Failed to parse GeoJSON");
        return null;
      }

      Geometry geometry = convertToJTSGeometry(geoJSONObject);
      cachedGeometry = geometry;
      return geometry;

    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to parse GeoJSON: " + geoJSON, e);
      return null;
    }
  }

  /** Convert a GeoJSON object to a JTS Geometry. */
  private Geometry convertToJTSGeometry(GeoJSON geoJSON) {
    if (geoJSON instanceof GeoJSONFeatureCollection) {
      return convertFeatureCollection((GeoJSONFeatureCollection) geoJSON);
    } else if (geoJSON instanceof GeoJSONFeature) {
      return convertFeature((GeoJSONFeature) geoJSON);
    } else if (geoJSON instanceof GeoJSONGeometry) {
      return convertGeometry((GeoJSONGeometry) geoJSON);
    }

    LOG.warning("Unknown GeoJSON type: " + geoJSON.getClass().getName());
    return null;
  }

  /** Convert a GeoJSONFeatureCollection to a JTS Geometry (union of all features). */
  private Geometry convertFeatureCollection(GeoJSONFeatureCollection featureCollection) {
    Geometry result = null;

    for (GeoJSONFeature feature : featureCollection.getFeatures()) {
      Geometry featureGeometry = convertFeature(feature);
      if (featureGeometry != null) {
        result = result == null ? featureGeometry : result.union(featureGeometry);
      }
    }

    return result;
  }

  /** Convert a GeoJSONFeature to a JTS Geometry. */
  private Geometry convertFeature(GeoJSONFeature feature) {
    GeoJSONGeometry geometry = feature.getGeometry();
    return geometry != null ? convertGeometry(geometry) : null;
  }

  /** Convert a GeoJSONGeometry to a JTS Geometry. */
  private Geometry convertGeometry(GeoJSONGeometry geometry) {
    if (geometry instanceof GeoJSONPoint) {
      return convertPoint((GeoJSONPoint) geometry);
    } else if (geometry instanceof GeoJSONPolygon) {
      return convertPolygon((GeoJSONPolygon) geometry);
    } else if (geometry instanceof GeoJSONMultiPolygon) {
      return convertMultiPolygon((GeoJSONMultiPolygon) geometry);
    }

    LOG.warning("Unsupported geometry type: " + geometry.getClass().getName());
    return null;
  }

  /** Convert GeoJSONPoint to JTS Point. */
  private Point convertPoint(GeoJSONPoint point) {
    return GEOMETRY_FACTORY.createPoint(point.getCoordinates());
  }

  /** Convert GeoJSONPolygon to JTS Polygon. */
  private Polygon convertPolygon(GeoJSONPolygon polygon) {
    Coordinate[] exteriorCoords = polygon.getExteriorRing();
    LinearRing shell = GEOMETRY_FACTORY.createLinearRing(exteriorCoords);

    if (polygon.hasHoles()) {
      java.util.List<Coordinate[]> holes = polygon.getHoles();
      LinearRing[] holeRings = new LinearRing[holes.size()];
      for (int i = 0; i < holes.size(); i++) {
        holeRings[i] = GEOMETRY_FACTORY.createLinearRing(holes.get(i));
      }
      return GEOMETRY_FACTORY.createPolygon(shell, holeRings);
    } else {
      return GEOMETRY_FACTORY.createPolygon(shell);
    }
  }

  /** Convert GeoJSONMultiPolygon to JTS MultiPolygon. */
  private MultiPolygon convertMultiPolygon(GeoJSONMultiPolygon multiPolygon) {
    java.util.List<GeoJSONPolygon> polygons = multiPolygon.getPolygons();
    Polygon[] jtsPolygons = new Polygon[polygons.size()];

    for (int i = 0; i < polygons.size(); i++) {
      jtsPolygons[i] = convertPolygon(polygons.get(i));
    }

    return GEOMETRY_FACTORY.createMultiPolygon(jtsPolygons);
  }

  @Override
  public Predicate<Object> asPredicate(Supplier<Long> currentMillisSupplier) {
    return obj -> {
      if (obj == null) return false;

      Coordinate coordinate;

      if (obj instanceof Coordinate) {
        coordinate = (Coordinate) obj;
      } else {
        coordinate =
            ValueUtil.getValue(obj, GeoJSONPoint.class)
                .map(GeoJSONPoint::getCoordinates)
                .orElse(null);
      }

      if (coordinate == null) {
        return false;
      }

      // Clamp coordinates to valid ranges
      coordinate =
          new Coordinate(
              Math.min(180d, Math.max(-180d, coordinate.x)),
              Math.min(90d, Math.max(-90d, coordinate.y)));

      Geometry geometry = parseGeometry();
      if (geometry == null) {
        LOG.warning("Failed to parse geometry for geofence check");
        return false;
      }

      // Create a Point from the coordinate
      Point point = GEOMETRY_FACTORY.createPoint(coordinate);

      // Check if the point is within the geometry
      boolean contains = geometry.contains(point);

      // Return based on negation
      return negated != contains;
    };
  }
}
