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
package org.openremote.model.query

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonMappingException
import org.hibernate.internal.util.SerializationHelper
import org.openremote.model.query.filter.*
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class AssetQueryCopyTest extends Specification {

  def "Copying an asset query preserves and isolates #predicate.class.simpleName"() {
    given:
    def original = new AssetQuery().attributes(new AttributePredicate("value", predicate))
    // Serialization is used only to compare all predicate options, not to make the copy.
    def expected = ValueUtil.JSON.writeValueAsString(predicate)

    when:
    def copied = new AssetQuery(original).attributes.items[0].value

    then:
    copied.class == predicate.class
    !copied.is(predicate)
    ValueUtil.JSON.writeValueAsString(copied) == expected

    when:
    mutate(predicate)

    then:
    ValueUtil.JSON.writeValueAsString(copied) == expected

    where:
    predicate | mutate
    new StringPredicate(AssetQuery.Match.CONTAINS, false, "match").negate(true) | {
      it.value = "next"
    }
    new BooleanPredicate(true) | { it.value = false }
    new NumberPredicate(new BigDecimal("1234567890.123456789"), AssetQuery.Operator.BETWEEN).rangeValue(99).negate(true) | {
      it.rangeValue = 100
    }
    new DateTimePredicate("2026-01-01", "2026-12-31").negate(true) | {
      it.rangeValue = "2027-01-01"
    }
    new ArrayPredicate([values: [1, 2]], 0, 3, 1, 5, true) | { it.value.values.add(3) }
    new RadialGeofencePredicate(100, 51, 4, true) | { it.radius = 200 }
    new RectangularGeofencePredicate(50, 3, 52, 5, true) | { it.latMax = 53 }
    new GeoJSONGeofencePredicate('{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}', true) | {
      it.geoJSON = null
    }
    new CalendarEventPredicate(new Date(1800000000000L)) | { it.timestamp.time = 0 }
    new ValueEmptyPredicate().negate(true) | { it.negate = false }
    new ValueAnyPredicate() | { }
    new ExtendedStringPredicate(value: "match", extra: "custom") | { it.extra = "next" }
  }

  def "Copying an asset query preserves null and empty options"() {
    given:
    def original = new AssetQuery(
            ids: [] as String[], names: [] as StringPredicate[], parents: [] as ParentPredicate[],
            paths: [] as PathPredicate[], userIds: [] as String[], types: [] as String[],
            select: new AssetQuery.Select().excludeAttributes(), attributes: new LogicGroup())
    original.attributes.items = []
    original.attributes.groups = []

    when:
    def copied = new AssetQuery(original)
    def blank = new AssetQuery(new AssetQuery())

    then:
    ["ids", "names", "parents", "paths", "userIds", "types"].every {
      copied[it].length == 0 && !copied[it].is(original[it]) && blank[it] == null
    }
    copied.select.attributes.length == 0
    !copied.select.attributes.is(original.select.attributes)
    copied.attributes.items.empty
    copied.attributes.groups.empty
    !copied.attributes.items.is(original.attributes.items)
    !copied.attributes.groups.is(original.attributes.groups)
    copied.@includeDeletePending == null
    blank.select == null
    blank.attributes == null
  }

  def "Copying location predicates retains their type and isolates their metadata"() {
    given:
    def predicate = new LocationAttributePredicate(new RadialGeofencePredicate(100, 51, 4))
    predicate.meta(new NameValuePredicate("label", new StringPredicate("original")))
    predicate.timestampOlderThan = "PT5M"

    when:
    def copied = new AssetQuery(new AssetQuery().attributes(predicate)).attributes.items[0]
    predicate.value.radius = 200
    predicate.meta[0].name.value = "next"
    predicate.timestampOlderThan = "PT10M"

    then:
    copied instanceof LocationAttributePredicate
    copied.value.radius == 100
    copied.meta[0].name.value == "label"
    copied.timestampOlderThan == "PT5M"
  }

  def "Copying array predicate payloads isolates nested mutable values"() {
    given:
    def payload = [numbers: [1, 2] as int[], records: [[value: "original"]],
      time: new Date(1800000000000L), tree: ValueUtil.JSON.readTree('{"value":1}'),
      counter: new java.util.concurrent.atomic.AtomicInteger(7)]
    def predicate = new ArrayPredicate(payload, null, null, null, null, false)

    when:
    def copied = new AssetQuery(new AssetQuery().attributes(new AttributePredicate("value", predicate)))
            .attributes.items[0].value.value
    payload.numbers[0] = 99
    payload.records[0].value = "next"
    payload.time.time = 0
    payload.tree.put("value", 2)
    payload.counter.set(8)

    then:
    copied.numbers instanceof int[]
    copied.numbers.toList() == [1, 2]
    copied.records == [[value: "original"]]
    copied.time.time == 1800000000000L
    copied.tree.get("value").intValue() == 1
    copied.counter.get() == 7
  }

  def "Copying an array predicate supports a non-Serializable record in #shape"() {
    given: "the payload is accepted by the same JSON conversion used by asset storage"
    def record = new Reading("original", ["first"], [] as Object[], null,
    new BigDecimal("1234567890.123456789"))
    assert record.class.record
    assert !(record instanceof Serializable)
    def payload = wrap(record)
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copiedPayload = new AssetQuery(original).attributes.items[0].value.value
    def copiedRecord = unwrap(copiedPayload)

    then: "copying retains the record type, filter representation and values"
    copiedPayload.getClass() == payload.getClass()
    copiedRecord instanceof Reading
    !copiedRecord.is(record)
    !copiedRecord.tags().is(record.tags())
    !copiedRecord.empty().is(record.empty())
    copiedRecord.empty().length == 0
    copiedRecord.absent() == null
    copiedRecord.amount() == new BigDecimal("1234567890.123456789")
    ValueUtil.asJSON(copiedPayload).orElseThrow() == expectedJson

    when: "the caller changes a mutable record component after the copy"
    record.tags().add("caller edit")

    then:
    copiedRecord.tags() == ["first"]
    ValueUtil.asJSON(copiedPayload).orElseThrow() == expectedJson

    when: "the copied component is changed independently"
    copiedRecord.tags().add("copy edit")

    then:
    record.tags() == ["first", "caller edit"]

    where:
    shape | wrap | unwrap
    "the value itself" | { it } | { it }
    "a list" | { [it] } | { it[0] }
    "a map" | { [reading: it] } | { it.reading }
    "a typed array" | { [it] as Reading[] } | { it[0] }
  }

  def "Copying an array predicate supports a non-Serializable mutable object"() {
    given:
    def payload = new MutableReading(label: "original", tags: ["first"])
    assert !(payload instanceof Serializable)
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value

    then:
    copied instanceof MutableReading
    !copied.is(payload)
    !copied.tags.is(payload.tags)
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson

    when:
    payload.label = "next"
    payload.tags.add("caller edit")

    then:
    copied.label == "original"
    copied.tags == ["first"]
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson
  }

  def "Copying an array predicate preserves a serialization-only payload in #shape for database queries"() {
    given: "storage only requires JSON serialization, not reconstruction of the original class"
    def payload = new SerializationOnlyReading("original", ["first"])
    assert !(payload instanceof Serializable)
    def payloadJson = ValueUtil.asJSON(payload).orElseThrow()
    assert ValueUtil.JSON.readTree(payloadJson) == ValueUtil.JSON.readTree(
    '{"label":"original","tags":["first"]}')
    payload.contents.amount = new BigDecimal("1234567890.123456789")
    def value = wrap(payload)
    def expectedJson = ValueUtil.asJSON(value).orElseThrow()
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(value, null, null, null, null, false)))

    when: "Jackson is asked to reconstruct the payload's original class"
    ValueUtil.JSON.readValue(payloadJson, SerializationOnlyReading)

    then: "a same-class JSON round trip cannot copy this otherwise valid database payload"
    thrown(JsonMappingException)

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value

    then: "the copied payload produces the same database filter without requiring its original type"
    copied != null
    !copied.is(payload)
    ValueUtil.JSON.readTree(ValueUtil.asJSON(copied).orElseThrow()) == ValueUtil.JSON.readTree(expectedJson)
    ValueUtil.asJSON(copied).orElseThrow().contains('"amount":1234567890.123456789')

    when:
    payload.contents.label = "next"
    payload.contents.tags.add("caller edit")
    payload.contents.amount = 0

    then:
    ValueUtil.JSON.readTree(ValueUtil.asJSON(copied).orElseThrow()) == ValueUtil.JSON.readTree(expectedJson)
    ValueUtil.asJSON(copied).orElseThrow().contains('"amount":1234567890.123456789')

    where:
    shape | wrap
    "the value itself" | { it }
    "a nested collection" | { [readings: [it]] }
    "a typed array" | { [it] as SerializationOnlyReading[] }
  }

  def "Copying an array predicate supports a Serializable object containing a non-Serializable record"() {
    given:
    def payload = new ReadingEnvelope(reading: new Reading("original", ["first"], [] as Object[], null,
    new BigDecimal("1234567890.123456789")))
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value
    payload.reading.tags().add("caller edit")

    then:
    copied instanceof ReadingEnvelope
    copied.reading.tags() == ["first"]
    copied.reading.empty().length == 0
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson
  }

  static record Reading(String label, List<String> tags, Object[] empty, String absent, BigDecimal amount) {}

  def "Copying an array predicate does not silently discard read-only JSON properties"() {
    given:
    def payload = new ReadOnlyReading(label: "original")
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    assert ValueUtil.JSON.readValue(expectedJson, ReadOnlyReading).label == null
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value
    payload.label = "next"

    then:
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson
  }

  def "Copying an array predicate preserves JSON-visible transient state in #shape"() {
    given: "Java serialization loses a field that contributes to the database predicate"
    def reading = new TransientReading("active")
    assert ValueUtil.asJSON(reading).orElseThrow() == '{"label":"active"}'
    def payload = wrap(reading)
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    assert ValueUtil.asJSON(SerializationHelper.clone(payload)).orElseThrow() != expectedJson
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value

    then: "the copied query keeps the original JSON containment filter"
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson

    when: "the caller changes the transient field after copying"
    reading.changeLabel("next")

    then:
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson

    where:
    shape | wrap
    "the value itself" | { it }
    "a nested collection" | { [readings: [it]] }
    "a typed array" | { [it] as TransientReading[] }
    "a Serializable wrapper" | { new TransientReadingEnvelope(reading: it) }
  }

  def "Copying an array predicate does not drop a payload whose Java deserialization returns null"() {
    given:
    def payload = new NullResolvingReading("active")
    assert SerializationHelper.clone(payload) == null
    def expectedJson = ValueUtil.asJSON(payload).orElseThrow()
    assert expectedJson == '{"label":"active"}'
    def original = new AssetQuery().attributes(
            new AttributePredicate("readings", new ArrayPredicate(payload, null, null, null, null, false)))

    when:
    def copied = new AssetQuery(original).attributes.items[0].value.value

    then: "null must not turn the containment filter into an unrestricted predicate"
    copied != null
    ValueUtil.asJSON(copied).orElseThrow() == expectedJson
  }

  def "Copying an array predicate rejects a payload that cannot be serialized instead of removing its filter"() {
    given:
    def predicate = new ArrayPredicate(new InvalidReading(), null, null, null, null, false)
    def original = new AssetQuery().attributes(new AttributePredicate("readings", predicate))

    when:
    new AssetQuery(original)

    then:
    thrown(IllegalArgumentException)
    predicate.value instanceof InvalidReading
  }

  static class ReadOnlyReading {
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    String label
  }

  static class TransientReading implements Serializable {
    private transient String label

    TransientReading(String label) {
      this.label = label
    }

    @JsonProperty
    String getLabel() {
      label
    }

    void changeLabel(String label) {
      this.label = label
    }
  }

  static class TransientReadingEnvelope implements Serializable {
    TransientReading reading
  }

  static class NullResolvingReading extends TransientReading {
    NullResolvingReading(String label) {
      super(label)
    }

    private Object readResolve() {
      null
    }
  }

  static class InvalidReading {
    @JsonValue
    Object jsonValue() {
      throw new IllegalStateException("Cannot serialize this payload")
    }
  }

  static class MutableReading {
    String label
    List<String> tags
  }

  static class ReadingEnvelope implements Serializable {
    Reading reading
  }

  static class SerializationOnlyReading {
    final Map<String, Object> contents

    SerializationOnlyReading(String label, List<String> tags) {
      contents = [label: label, tags: tags]
    }

    @JsonValue
    Map<String, Object> jsonValue() {
      contents
    }
  }

  static class ExtendedStringPredicate extends StringPredicate {
    String extra
  }
}
