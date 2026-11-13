/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.model.util;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.openremote.model.syslog.SyslogCategory;

/**
 * Basic cron expression parser (unfortunately the quartz cron expression class uses all protected
 * methods and class is final).
 */
public class CronExpressionParser implements Serializable {

  private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, CronExpressionParser.class);

  enum DaysOfWeek {
    SUN,
    MON,
    TUE,
    WED,
    THU,
    FRI,
    SAT;

    private static DaysOfWeek[] values = DaysOfWeek.values();
    static final EnumSet<DaysOfWeek> ALL_OPTS = EnumSet.allOf(DaysOfWeek.class);

    public static EnumSet<DaysOfWeek> fromString(String str) {
      String[] arr = str.split(",");
      EnumSet<DaysOfWeek> set = EnumSet.noneOf(DaysOfWeek.class);

      for (String e : arr) {
        DaysOfWeek daysOfWeek = null;

        try {
          int i = Integer.parseInt(e);
          daysOfWeek = values[i - 1];
        } catch (NumberFormatException ex) {
          try {
            daysOfWeek = DaysOfWeek.valueOf(e.trim().toUpperCase(Locale.ROOT));
          } catch (Exception ex2) {
            LOG.log(Level.INFO, "Cannot convert cron value for days of week to a day", ex2);
          }
        }
        if (daysOfWeek != null && !set.contains(daysOfWeek)) {
          set.add(daysOfWeek);
        }
      }
      return set;
    }
  }

  enum Months {
    JAN,
    FEB,
    MAR,
    APR,
    MAY,
    JUN,
    JUL,
    AUG,
    SEP,
    OCT,
    NOV,
    DEC;

    private static Months[] values = Months.values();
    static final EnumSet<Months> ALL_OPTS = EnumSet.allOf(Months.class);

    public static EnumSet<Months> fromString(String str) {
      String[] arr = str.split(",");
      EnumSet<Months> set = EnumSet.noneOf(Months.class);

      for (String e : arr) {
        Months months = null;

        try {
          int i = Integer.parseInt(e);
          months = values[i - 1];
        } catch (NumberFormatException ex) {
          try {
            months = Months.valueOf(e.trim().toUpperCase(Locale.ROOT));
          } catch (Exception ex2) {
            LOG.log(Level.INFO, "Cannot convert cron value for days of week to a day", ex2);
          }
        }
        if (months != null && !set.contains(months)) {
          set.add(months);
        }
      }
      return set;
    }
  }

  protected final String originalCronExpression;
  protected static final int SECONDS = 0;
  protected static final int MINUTES = 1;
  protected static final int HOURS = 2;
  protected static final int DAY_OF_MONTH = 0;
  protected static final int MONTH = 1;
  protected static final int DAY_OF_WEEK = 2;
  protected static final int YEAR = 3;
  protected int[] timeValues = new int[3];
  protected String[] otherValues = new String[4];
  protected boolean compatible = true;
  protected boolean valid;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public CronExpressionParser(String cronExpression) {
    valid = Pattern.matches(TextUtil.REGEXP_PATTERN_CRON_EXPRESSION, cronExpression);
    originalCronExpression = cronExpression;
    String[] fields = cronExpression.split("\\s+");

    if (fields.length < 6 || fields.length > 7) {
      LOG.info("Cron expression doesn't contain the required 6 or 7 space separated values");
      compatible = false;
    } else {
      for (int i = 0; i < 3; i++) {
        Integer value = TextUtil.asInteger(fields[i]).orElse(null);
        if (value == null) {
          compatible = false;
        } else {
          timeValues[i] = value;
        }
      }

      // Initialize empty year value
      otherValues[3] = "";

      System.arraycopy(fields, 3, otherValues, 0, fields.length - 3);
    }
  }

  public boolean isCompatible() {
    return compatible;
  }

  public boolean isValid() {
    return valid;
  }

  @JsonValue
  public String buildCronExpression() {
    if (!isCompatible()) {
      return originalCronExpression;
    } else {
      return timeValues[0]
          + " "
          + timeValues[1]
          + " "
          + timeValues[2]
          + " "
          + String.join(" ", otherValues).trim();
    }
  }

  public void setTime(int hours, int minutes, int seconds) {
    hours = Math.max(0, hours % 24);
    minutes = Math.max(0, minutes % 60);
    seconds = Math.max(0, seconds % 60);
    compatible = true;

    timeValues[HOURS] = hours;
    timeValues[MINUTES] = minutes;
    timeValues[SECONDS] = seconds;
  }

  public String getFormattedTime() {
    return String.format(
        "%02d:%02d:%02d", timeValues[HOURS], timeValues[MINUTES], timeValues[SECONDS]);
  }
}
