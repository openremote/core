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
import type { JsonSchema } from ".";

export const schemas: JsonSchema[] = [
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "String",
    type: "string",
    "or:test:value": "test",
    "or:test:expected": "test",
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Boolean",
    type: "boolean",
    "or:test:value": true,
    "or:test:expected": true,
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Integer",
    type: "integer",
    "or:test:value": 1,
    "or:test:expected": 1,
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Number",
    type: "number",
    "or:test:value": 1,
    "or:test:expected": 1,
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Object",
    type: "object",
    "or:test:expected": { value: "test" },
    "or:test:props": ["value"],
    properties: {
      value: {
        type: "string",
        "or:test:value": "test",
      },
    },
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Array",
    type: "array",
    "or:test:expected": ["test"],
    "or:test:item:count": 1,
    items: {
      title: "String",
      type: "string",
      "or:test:value": "test",
    },
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "3 Dimensional Array",
    type: "array",
    "or:test:expected": [
      [
        ["test", "test"],
        ["test", "test"],
      ],
      [
        ["test", "test"],
        ["test", "test"],
      ],
    ],
    "or:test:item:count": 2,
    items: {
      title: "2 Dimensional Array",
      type: "array",
      "or:test:item:count": 2,
      items: {
        title: "Array",
        type: "array",
        "or:test:item:count": 2,
        items: {
          title: "String",
          type: "string",
          "or:test:value": "test",
        },
      },
    },
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Complex",
    type: "array",
    // The boolean is filled and then deleted again, so it is absent here
    "or:test:expected": [
      { unresolvedBoolean: false, string: "test", array: ["test", "test"] },
      { unresolvedBoolean: false, string: "test", array: ["test", "test"] },
    ],
    "or:test:item:count": 2,
    items: {
      title: "Object",
      type: "object",
      required: ["unresolvedBoolean"],
      "or:test:props": ["string", "array", "boolean"],
      properties: {
        string: {
          title: "String",
          type: "string",
          "or:test:value": "test",
        },
        boolean: {
          title: "Boolean",
          type: "boolean",
          "or:test:value": true,
          "or:test:remove": true,
        },
        // Should resolve to false
        unresolvedBoolean: {
          title: "Unresolved boolean",
          type: "boolean",
          default: false,
        },
        array: {
          title: "Array",
          type: "array",
          "or:test:item:count": 2,
          items: {
            title: "String",
            type: "string",
            "or:test:value": "test",
          },
        },
      },
    },
  },
  {
    $schema: "http://json-schema.org/draft-07/schema#",
    title: "Polymorphic",
    type: "array",
    "or:test:expected": [
      { customType: "SubType", value: "test" },
      { customType: "CustomSubType", value: "test" },
    ],
    definitions: {
      SubType: {
        title: "Sub Type",
        type: "object",
        "or:test:props": ["value"],
        properties: {
          customType: {
            const: "SubType",
          },
          value: {
            title: "Value",
            type: "string",
            "or:test:value": "test",
          },
        },
        required: ["customType"],
      },
      CustomSubType: {
        title: "Custom Sub Type",
        type: "object",
        "or:test:props": ["value"],
        properties: {
          customType: {
            const: "CustomSubType",
          },
          value: {
            title: "Value",
            type: "string",
            "or:test:value": "test",
          },
        },
        required: ["customType"],
      },
    },
    items: {
      type: "object",
      discriminator: {
        propertyName: "customType",
      },
      oneOf: [{ $ref: "#/definitions/SubType" }, { $ref: "#/definitions/CustomSubType" }],
    },
  },
];
