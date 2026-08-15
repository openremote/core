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
package org.openremote.model;

import org.openremote.model.util.TsIgnore;

@TsIgnore
public interface Constants {

  String KEYCLOAK_CLIENT_ID = "openremote";
  String MANAGER_CLIENT_ID = "manager-keycloak";
  String MASTER_REALM = "master";
  String MASTER_REALM_ADMIN_USER = "admin";
  String SUPER_USER_REALM_ROLE = "admin";
  String READ_LOGS_ROLE = "read:logs";
  String READ_USERS_ROLE = "read:users";
  String READ_ADMIN_ROLE = "read:admin";
  String READ_MAP_ROLE = "read:map";
  String READ_ASSETS_ROLE = "read:assets";
  String READ_RULES_ROLE = "read:rules";
  String READ_INSIGHTS_ROLE = "read:insights";
  String READ_ALARMS_ROLE = "read:alarms";
  String READ_NOTIFICATIONS_ROLE = "read:notifications";
  String READ_SERVICES_ROLE = "read:services";
  String READ_TUNNELS_ROLE = "read:tunnels";
  String WRITE_SERVICES_ROLE = "write:services";
  String WRITE_USER_ROLE = "write:user";
  String WRITE_ADMIN_ROLE = "write:admin";
  String WRITE_LOGS_ROLE = "write:logs";
  String WRITE_ASSETS_ROLE = "write:assets";
  String WRITE_ATTRIBUTES_ROLE = "write:attributes";
  String WRITE_RULES_ROLE = "write:rules";
  String WRITE_INSIGHTS_ROLE = "write:insights";
  String WRITE_ALARMS_ROLE = "write:alarms";
  String WRITE_NOTIFICATIONS_ROLE = "write:notifications";
  String WRITE_TUNNELS_ROLE = "write:tunnels";
  String RESTRICTED_USER_REALM_ROLE = "restricted_user";
  String AUTH_CONTEXT = "AUTH_CONTEXT";
  String SESSION_OPEN = "connection.sessionOpen";
  String SESSION_CLOSE = "connection.sessionClose";
  String SESSION_CLOSE_ERROR = "connection.sessionCloseError";
  int ACCESS_TOKEN_LIFESPAN_SECONDS = 60; // 1 minute
  String PERSISTENCE_SEQUENCE_ID_GENERATOR = "OPENREMOTE_SEQUENCE";

  String OR_HOSTNAME = "OR_HOSTNAME";
  String OR_ADDITIONAL_HOSTNAMES = "OR_ADDITIONAL_HOSTNAMES";
  String OR_SSL_PORT = "OR_SSL_PORT";
  String OR_EMAIL_USER = "OR_EMAIL_USER";
  String OR_EMAIL_HOST = "OR_EMAIL_HOST";
  String OR_EMAIL_OAUTH2_URL = "OR_EMAIL_OAUTH2_URL";
  String OR_EMAIL_OAUTH2_CLIENT_ID = "OR_EMAIL_OAUTH2_CLIENT_ID";
  String OR_EMAIL_OAUTH2_CLIENT_SECRET = "OR_EMAIL_OAUTH2_CLIENT_SECRET";
  String OR_EMAIL_OAUTH2_SCOPES = "OR_EMAIL_OAUTH2_SCOPES";
  String OR_EMAIL_PASSWORD = "OR_EMAIL_PASSWORD";
  String OR_EMAIL_PORT = "OR_EMAIL_PORT";
  int OR_EMAIL_PORT_DEFAULT = 587;
  String OR_MQTT_MTLS_SERVER_LISTEN_HOST = "OR_MQTT_MTLS_SERVER_LISTEN_HOST";
  String OR_MQTT_MTLS_SERVER_LISTEN_PORT = "OR_MQTT_MTLS_SERVER_LISTEN_PORT";
  String OR_MQTT_MTLS_KEYSTORE_PATH = "OR_MQTT_MTLS_KEYSTORE_PATH";
  String OR_MQTT_MTLS_KEYSTORE_PASSWORD = "OR_MQTT_MTLS_KEYSTORE_PASSWORD";
  String OR_MQTT_MTLS_TRUSTSTORE_PATH = "OR_MQTT_MTLS_TRUSTSTORE_PATH";
  String OR_MQTT_MTLS_TRUSTSTORE_PASSWORD = "OR_MQTT_MTLS_TRUSTSTORE_PASSWORD";
  String OR_MQTT_MTLS_DISABLED = "OR_MQTT_MTLS_DISABLED";
  // Enabled by default, but the acceptor only starts once a keystore and truststore are in place
  boolean OR_MQTT_MTLS_DISABLED_DEFAULT = false;
  int OR_MQTT_MTLS_PORT_DEFAULT = 8884;
  // Relative to the storage dir, so the defaults follow OR_STORAGE_DIR
  String OR_MQTT_MTLS_KEYSTORE_DIR_DEFAULT = "keystores";
  String OR_MQTT_MTLS_KEYSTORE_FILE_DEFAULT = "server_keystore.p12";
  String OR_MQTT_MTLS_TRUSTSTORE_FILE_DEFAULT = "server_truststore.p12";
  String OR_MQTT_MTLS_KEYSTORE_PASSWORD_DEFAULT = "secret";
  String OR_EMAIL_TLS = "OR_EMAIL_TLS";
  String OR_EMAIL_PROTOCOL = "OR_EMAIL_PROTOCOL";
  String OR_EMAIL_PROTOCOL_DEFAULT = "smtp";
  boolean OR_EMAIL_TLS_DEFAULT = true;
  String OR_EMAIL_X_HEADERS = "OR_EMAIL_X_HEADERS";
  String OR_EMAIL_FROM = "OR_EMAIL_FROM";
  String OR_EMAIL_FROM_DEFAULT = "no-reply@localhost";
  String OR_EMAIL_ADMIN = "OR_EMAIL_ADMIN";
  String REALM_PARAM_NAME = "Realm";
  String ASSET_ID_REGEXP = "^[0-9A-Za-z]{22}$";
  String UUID_REGEXP =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  String EMAIL_REGEXP =
      "(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])";
  String ISO8601_DURATION_REGEXP =
      "^P(?!$)((\\d+Y)|(\\d+\\.\\d+Y$))?((\\d+M)|(\\d+\\.\\d+M$))?((\\d+W)|(\\d+\\.\\d+W$))?((\\d+D)|(\\d+\\.\\d+D$))?(T(?=\\d)((\\d+H)|(\\d+\\.\\d+H$))?((\\d+M)|(\\d+\\.\\d+M$))?(\\d+(\\.\\d+)?S)?)??$";
  String ISO8601_DATETIME_REGEXP =
      "^(?:[1-9]\\d{3}-(?:(?:0[1-9]|1[0-2])-(?:0[1-9]|1\\d|2[0-8])|(?:0[13-9]|1[0-2])-(?:29|30)|(?:0[13578]|1[02])-31)|(?:[1-9]\\d(?:0[48]|[2468][048]|[13579][26])|(?:[2468][048]|[13579][26])00)-02-29)T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:[\\.,]\\d{1,9})?(?:Z|[+-][01]\\d:[0-5]\\d)$";
  String HOSTNAME_OR_IP_REGEXP =
      "((^\\s*((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)))|(^\\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?)|^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9]))+(\\.([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9]))*)$";
  String IP_REGEXP =
      "((^\\s*((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)))|(^\\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?))$";
  String HTTP_URL_REGEXP =
      "^(http:\\/\\/www\\.|https:\\/\\/www\\.|http:\\/\\/|https:\\/\\/)?((((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)))|(((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?)|(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9]))+(\\.([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9]))*)(:[0-9]{1,5})?(\\/.*)?$";
  String WS_URL_REGEXP =
      "^(ws:\\/\\/www\\.|wss:\\/\\/www\\.|ws:\\/\\/|wss:\\/\\/)?((((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)))|(((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?)|(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9]))+(\\.([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9]))*)(:[0-9]{1,5})?(\\/.*)?$";
  String DYNAMIC_VALUE_PLACEHOLDER_REGEXP = "\\%VALUE(?::([^:]+))?\\%";
  String DYNAMIC_TIME_PLACEHOLDER_REGEXP = "\\%TIME([+-]P\\w*)?(?::(.+?))?\\%";

  static String[] units(String... units) {
    return units;
  }

  String UNITS_PER = "per";
  String UNITS_SQUARED = "squared";
  String UNITS_CUBED = "cubed";
  String UNITS_PEAK = "peak";
  String UNITS_MEGA = "mega";
  String UNITS_GIGA = "giga";
  String UNITS_TERA = "tera";
  String UNITS_KILO = "kilo";
  String UNITS_CENTI = "centi";
  String UNITS_HECTO = "hecto";
  String UNITS_MICRO = "micro";
  String UNITS_MILLI = "milli";
  String UNITS_PERCENTAGE = "percentage";
  String UNITS_ACRE = "acre";
  String UNITS_HECTARE = "hectare";
  String UNITS_DECIBEL = "decibel";
  String UNITS_DECIBEL_ATTENUATED = "decibel_attenuated";
  String UNITS_KNOT = "knot";
  String UNITS_CELSIUS = "celsius";
  String UNITS_KELVIN = "kelvin";
  String UNITS_FAHRENHEIT = "fahrenheit";
  String UNITS_YEAR = "year";
  String UNITS_MONTH = "month";
  String UNITS_WEEK = "week";
  String UNITS_DAY = "day";
  String UNITS_HOUR = "hour";
  String UNITS_MINUTE = "minute";
  String UNITS_SECOND = "second";
  String UNITS_METRE = "metre";
  String UNITS_INCH = "inch";
  String UNITS_FOOT = "foot";
  String UNITS_YARD = "yard";
  String UNITS_MILE = "mile";
  String UNITS_MILE_SCANDINAVIAN = "mile_scandinavian";
  String UNITS_GRAM = "gram";
  String UNITS_OUNCE = "ounce";
  String UNITS_MASS_POUND = "pound";
  String UNITS_STONE = "stone";
  String UNITS_DEGREE = "degree";
  String UNITS_RADIAN = "radian";
  String UNITS_LITRE = "litre";
  String UNITS_GALLON = "gallon";
  String UNITS_FLUID_OUNCE = "fluid_ounce";
  String UNITS_JOULE = "joule";
  String UNITS_BTU = "btu";
  String UNITS_WATT = "watt";
  String UNITS_LUX = "lux";
  String UNITS_LUMEN = "lumen";
  String UNITS_PASCAL = "pascal";
  String UNITS_BAR = "bar";
  String UNITS_IN_HG = "inch_mercury";
  String UNITS_VOLT = "volt";
  String UNITS_OHM = "ohm";
  String UNITS_AMP = "amp";
  String UNITS_HERTZ = "hertz";
  String UNITS_RPM = "rpm";
  String UNITS_PART_PER_MILLION = "ppm";
  String UNITS_CARBON = "carbon";
  String UNITS_VAR = "var";

  static boolean containsDynamicValuePlaceholder(String str) {
    return str.contains("%VALUE");
  }

  static boolean containsDynamicTimePlaceholder(String str) {
    return str.contains("%TIME");
  }
}
