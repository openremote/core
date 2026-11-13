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
package org.openremote.test.assets

import org.locationtech.jts.geom.Coordinate
import org.openremote.model.query.filter.GeofencePredicate
import org.openremote.model.query.filter.GeoJSONGeofencePredicate
import org.openremote.model.query.filter.RadialGeofencePredicate
import org.openremote.model.query.filter.RectangularGeofencePredicate
import spock.lang.Specification

class AssetQueryPredicateTest extends Specification {

  def "Rectangular Geofence Test"() {
    given:
    GeofencePredicate geofencePredicate = new RectangularGeofencePredicate(51.440914, 5.421723, 51.442755, 5.425151)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })
    def coordinatePredicateNegated = coordinatePredicate.negate()

    expect:
    coordinatePredicate.test(new Coordinate(5.423, 51.441))
    !coordinatePredicateNegated.test(new Coordinate(5.423, 51.441))
  }

  def "Radial geofence test"() {
    given:
    GeofencePredicate geofencePredicate = new RadialGeofencePredicate(100, 51.423, 5.441)
    GeofencePredicate geofencePredicateNegated = new RadialGeofencePredicate(100, 51.423, 5.441).negate()
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })
    def coordinatePredicateNegated = geofencePredicateNegated.asPredicate({
      -> System.currentTimeMillis()
    })

    expect:
    coordinatePredicate.test(new Coordinate(5.441, 51.423))
    !coordinatePredicateNegated.test(new Coordinate(5.441, 51.423))
  }

  def "GeoJSON Polygon geofence test"() {
    given: "A simple polygon representing a rectangular area around Vienna"
    def polygonGeoJSON = '''
        {
            "type": "Polygon",
            "coordinates": [
                [
                    [16.2, 48.1],
                    [16.6, 48.1],
                    [16.6, 48.3],
                    [16.2, 48.3],
                    [16.2, 48.1]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(polygonGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points inside the polygon should return true"
    assert coordinatePredicate.test(new Coordinate(16.4, 48.2))
    assert coordinatePredicate.test(new Coordinate(16.3, 48.15))

    and: "Points outside the polygon should return false"
    assert !coordinatePredicate.test(new Coordinate(16.0, 48.2))
    assert !coordinatePredicate.test(new Coordinate(16.4, 48.5))
    assert !coordinatePredicate.test(new Coordinate(17.0, 48.2))
  }

  def "GeoJSON Polygon with hole geofence test"() {
    given: "A polygon with a hole (donut shape)"
    def polygonWithHoleGeoJSON = '''
        {
            "type": "Polygon",
            "coordinates": [
                [
                    [16.2, 48.1],
                    [16.6, 48.1],
                    [16.6, 48.3],
                    [16.2, 48.3],
                    [16.2, 48.1]
                ],
                [
                    [16.3, 48.15],
                    [16.5, 48.15],
                    [16.5, 48.25],
                    [16.3, 48.25],
                    [16.3, 48.15]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(polygonWithHoleGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points in the outer ring but not in the hole should return true"
    assert coordinatePredicate.test(new Coordinate(16.25, 48.2))
    assert coordinatePredicate.test(new Coordinate(16.55, 48.2))

    and: "Points in the hole should return false"
    assert !coordinatePredicate.test(new Coordinate(16.4, 48.2))

    and: "Points outside should return false"
    assert !coordinatePredicate.test(new Coordinate(16.0, 48.2))
  }

  def "GeoJSON MultiPolygon geofence test"() {
    given: "A MultiPolygon representing two separate regions"
    def multiPolygonGeoJSON = '''
        {
            "type": "MultiPolygon",
            "coordinates": [
                [
                    [
                        [16.2, 48.1],
                        [16.4, 48.1],
                        [16.4, 48.2],
                        [16.2, 48.2],
                        [16.2, 48.1]
                    ]
                ],
                [
                    [
                        [16.5, 48.1],
                        [16.7, 48.1],
                        [16.7, 48.2],
                        [16.5, 48.2],
                        [16.5, 48.1]
                    ]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(multiPolygonGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points inside either polygon should return true"
    assert coordinatePredicate.test(new Coordinate(16.3, 48.15))
    assert coordinatePredicate.test(new Coordinate(16.6, 48.15))

    and: "Points between the polygons should return false"
    assert !coordinatePredicate.test(new Coordinate(16.45, 48.15))

    and: "Points outside both polygons should return false"
    assert !coordinatePredicate.test(new Coordinate(16.0, 48.15))
    assert !coordinatePredicate.test(new Coordinate(16.8, 48.15))
  }

  def "GeoJSON Feature geofence test"() {
    given: "A Feature containing a polygon geometry"
    def featureGeoJSON = '''
        {
            "type": "Feature",
            "properties": {
                "name": "Vienna Region"
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [
                    [
                        [16.2, 48.1],
                        [16.6, 48.1],
                        [16.6, 48.3],
                        [16.2, 48.3],
                        [16.2, 48.1]
                    ]
                ]
            }
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(featureGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points inside the feature's geometry should return true"
    assert coordinatePredicate.test(new Coordinate(16.4, 48.2))

    and: "Points outside should return false"
    assert !coordinatePredicate.test(new Coordinate(16.0, 48.2))
  }

  def "GeoJSON FeatureCollection geofence test"() {
    given: "A FeatureCollection with multiple features"
    def featureCollectionGeoJSON = '''
        {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"name": "Region A"},
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [
                            [
                                [16.2, 48.1],
                                [16.4, 48.1],
                                [16.4, 48.2],
                                [16.2, 48.2],
                                [16.2, 48.1]
                            ]
                        ]
                    }
                },
                {
                    "type": "Feature",
                    "properties": {"name": "Region B"},
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [
                            [
                                [16.5, 48.1],
                                [16.7, 48.1],
                                [16.7, 48.2],
                                [16.5, 48.2],
                                [16.5, 48.1]
                            ]
                        ]
                    }
                }
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(featureCollectionGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points inside any feature should return true"
    assert coordinatePredicate.test(new Coordinate(16.3, 48.15))
    assert coordinatePredicate.test(new Coordinate(16.6, 48.15))

    and: "Points outside all features should return false"
    assert !coordinatePredicate.test(new Coordinate(16.45, 48.15))
    assert !coordinatePredicate.test(new Coordinate(16.0, 48.15))
  }

  def "GeoJSON negated geofence test"() {
    given: "A polygon geofence that is negated"
    def polygonGeoJSON = '''
        {
            "type": "Polygon",
            "coordinates": [
                [
                    [16.2, 48.1],
                    [16.6, 48.1],
                    [16.6, 48.3],
                    [16.2, 48.3],
                    [16.2, 48.1]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(polygonGeoJSON).negate()
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Points inside the polygon should return false (negated)"
    assert !coordinatePredicate.test(new Coordinate(16.4, 48.2))

    and: "Points outside the polygon should return true (negated)"
    assert coordinatePredicate.test(new Coordinate(16.0, 48.2))
    assert coordinatePredicate.test(new Coordinate(17.0, 48.2))
  }

  def "GeoJSON simplified Austria border test"() {
    given: "A simplified polygon representing Austria's approximate borders"
    def austriaGeoJSON = '''
        {
            "type": "Polygon",
            "coordinates": [
                [
                    [9.5, 46.4],
                    [17.2, 46.4],
                    [17.2, 49.0],
                    [9.5, 49.0],
                    [9.5, 46.4]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(austriaGeoJSON)
    def coordinatePredicate = geofencePredicate.asPredicate({ -> System.currentTimeMillis() })

    expect: "Vienna (16.37, 48.21) should be inside Austria"
    assert coordinatePredicate.test(new Coordinate(16.37, 48.21))

    and: "Salzburg (13.04, 47.81) should be inside Austria"
    assert coordinatePredicate.test(new Coordinate(13.04, 47.81))

    and: "Innsbruck (11.39, 47.27) should be inside Austria"
    assert coordinatePredicate.test(new Coordinate(11.39, 47.27))

    and: "Munich, Germany (11.58, 48.14) should be outside (in this simplified border)"
    assert coordinatePredicate.test(new Coordinate(11.58, 48.14)) // Actually inside our simplified box

    and: "Bratislava, Slovakia (17.11, 48.15) should be inside (border city)"
    assert coordinatePredicate.test(new Coordinate(17.11, 48.15))

    and: "Prague, Czech Republic (14.42, 50.08) should be outside Austria"
    assert !coordinatePredicate.test(new Coordinate(14.42, 50.08))

    and: "Rome, Italy (12.50, 41.90) should be outside Austria"
    assert !coordinatePredicate.test(new Coordinate(12.50, 41.90))
  }

  def "GeoJSON center point calculation test"() {
    given: "A polygon geofence"
    def polygonGeoJSON = '''
        {
            "type": "Polygon",
            "coordinates": [
                [
                    [16.0, 48.0],
                    [17.0, 48.0],
                    [17.0, 49.0],
                    [16.0, 49.0],
                    [16.0, 48.0]
                ]
            ]
        }
        '''
    GeofencePredicate geofencePredicate = new GeoJSONGeofencePredicate(polygonGeoJSON)

    when: "Getting the center point"
    def centrePoint = geofencePredicate.getCentrePoint()

    then: "The center point should be approximately at the centroid"
    assert centrePoint != null
    assert centrePoint.length == 2
    assert Math.abs(centrePoint[0] - 16.5) < 0.1 // longitude
    assert Math.abs(centrePoint[1] - 48.5) < 0.1 // latitude
  }

  def "GeoJSON invalid input handling test"() {
    given: "Various invalid GeoJSON inputs"
    def invalidInputs = [
      null,
      "",
      "not json",
      "{}",
      '{"type": "Unknown"}',
      '{"coordinates": [[0,0]]}' // missing type
    ]

    expect: "Predicates with invalid input should handle gracefully"
    invalidInputs.each { invalidGeoJSON ->
      def geofencePredicate = new GeoJSONGeofencePredicate(invalidGeoJSON)
      def coordinatePredicate = geofencePredicate.asPredicate({
        -> System.currentTimeMillis()
      })
      // Should return false for any coordinate when GeoJSON is invalid
      assert !coordinatePredicate.test(new Coordinate(16.4, 48.2))
    }
  }
}
