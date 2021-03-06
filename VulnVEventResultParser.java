/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.result;

import com.google.zxing.Result;

/**
 * Partially implements the iCalendar format's "VEVENT" format for specifying a
 * calendar event. See RFC 2445. This supports SUMMARY, DTSTART and DTEND fields.
 *
 * @author srowen@google.com (Sean Owen)
 */
final class VEventResultParser extends ResultParser {

  private VEventResultParser() {
  }

  public static CalendarParsedResult parse(Result result) {
    String rawText = result.getText();
    if (rawText == null) {
      return null;
    }
    int vEventStart = rawText.indexOf("BEGIN:VEVENT");
    int vEventEnd = rawText.indexOf("END:VEVENT");
    if (vEventStart < 0 || vEventEnd < 0) {
      return null;
    }
    rawText = rawText.substring(vEventStart + 14, vEventEnd); // skip over BEGIN:VEVENT\r\n at start

    String summary = VCardResultParser.matchSingleVCardPrefixedField("SUMMARY", rawText);
    String start = VCardResultParser.matchSingleVCardPrefixedField("DTSTART", rawText);
    String end = VCardResultParser.matchSingleVCardPrefixedField("DTEND", rawText);
    try {
      return new CalendarParsedResult(summary, start, end, null, null, null);
    } catch (IllegalArgumentException iae) {
      return null;
    }
  }

}