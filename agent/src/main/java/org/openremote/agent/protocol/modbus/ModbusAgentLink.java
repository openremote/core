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
package org.openremote.agent.protocol.modbus;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;
import java.math.BigInteger;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.util.JSONSchemaUtil.JsonSchemaTemplateParameter;
import org.openremote.model.util.JSONSchemaUtil.JsonSchemaTitle;

public class ModbusAgentLink extends AgentLink<ModbusAgentLink> {

  @JsonPropertyDescription(
      "Modbus unit ID (1-255). Required for Serial, optional for TCP (defaults to 1)")
  @JsonSchemaTitle("Unit ID")
  @JsonSchemaTemplateParameter
  @Min(1) private Integer unitId;

  @JsonPropertyDescription(
      "Request interval in milliseconds. If not set: reads execute once on connection, writes execute on demand only.")
  @Min(1000) private Long requestInterval;

  @JsonPropertyDescription("Memory area to read from during read request")
  private ReadMemoryArea readMemoryArea;

  @JsonPropertyDescription("Type to convert the returned data to.")
  private ModbusDataType readValueType;

  @JsonPropertyDescription(
      "1-based address from which the value is read from (e.g., HOLDING register 1 = 40001)")
  private Integer readAddress;

  @JsonPropertyDescription("Memory area to write to. \"HOLDING\" or \"COIL\" allowed.")
  private WriteMemoryArea writeMemoryArea;

  @JsonPropertyDescription(
      "1-based address to which the value is written to (e.g., HOLDING register 1 = 40001)")
  private Integer writeAddress;

  @JsonPropertyDescription(
      "Set amount of registers to read/write. If left empty or less than 1, will use the default size for the corresponding data-type.")
  @JsonAlias("readRegisterAmount") // Support old field name for backward compatibility
  private Integer registersAmount;

  public Integer getUnitId() {
    return unitId;
  }

  public void setUnitId(Integer unitId) {
    this.unitId = unitId;
  }

  public Long getRequestInterval() {
    return requestInterval;
  }

  public void setRequestInterval(Long requestInterval) {
    this.requestInterval = requestInterval;
  }

  public ReadMemoryArea getReadMemoryArea() {
    return readMemoryArea;
  }

  public void setReadMemoryArea(ReadMemoryArea readMemoryArea) {
    this.readMemoryArea = readMemoryArea;
  }

  public ModbusDataType getReadValueType() {
    return readValueType;
  }

  public void setReadValueType(ModbusDataType readValueType) {
    this.readValueType = readValueType;
  }

  public Integer getReadAddress() {
    return readAddress;
  }

  public void setReadAddress(Integer readAddress) {
    this.readAddress = readAddress;
  }

  public WriteMemoryArea getWriteMemoryArea() {
    return writeMemoryArea;
  }

  public void setWriteMemoryArea(WriteMemoryArea writeMemoryArea) {
    this.writeMemoryArea = writeMemoryArea;
  }

  public Integer getWriteAddress() {
    return writeAddress;
  }

  public void setWriteAddress(Integer writeAddress) {
    this.writeAddress = writeAddress;
  }

  public Integer getRegistersAmount() {
    return registersAmount;
  }

  public void setRegistersAmount(Integer registersAmount) {
    this.registersAmount = registersAmount;
  }

  public enum ReadMemoryArea {
    COIL,
    DISCRETE,
    HOLDING,
    INPUT
  }

  public enum ModbusDataType {
    BOOL(boolean.class, 1),
    SINT(byte.class, 1),
    USINT(short.class, 1),
    BYTE(short.class, 1),
    INT(short.class, 1),
    UINT(int.class, 1),
    WORD(int.class, 1),
    DINT(int.class, 2),
    UDINT(long.class, 2),
    DWORD(long.class, 2),
    LINT(long.class, 4),
    ULINT(BigInteger.class, 4),
    LWORD(BigInteger.class, 4),
    REAL(float.class, 2),
    LREAL(double.class, 4),
    CHAR(char.class, 1),
    WCHAR(String.class, 1); // Assumes single wchar per entry

    private final Class<?> javaType;
    private final Integer registerCount;

    ModbusDataType(Class<?> javaType, Integer registerCount) {
      this.javaType = javaType;
      this.registerCount = registerCount;
    }

    public Class<?> getJavaType() {
      return javaType;
    }

    public Integer getRegisterCount() {
      return registerCount;
    }
  }

  public enum WriteMemoryArea {
    COIL,
    HOLDING
  }

  public ModbusAgentLink(String id) {
    super(id);
  }

  protected ModbusAgentLink() {}
}
