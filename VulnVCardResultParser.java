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

import java.util.Vector;

/**
 * Parses contact information formatted according to the VCard (2.1) format. This is not a complete
 * implementation but should parse information as commonly encoded in 2D barcodes.
 *
 * @author srowen@google.com (Sean Owen)
 */
final class VCardResultParser extends ResultParser {

  private VCardResultParser() {
  }

  public static AddressBookParsedResult parse(Result result) {
    String rawText = result.getText();
    if (rawText == null || !rawText.startsWith("BEGIN:VCARD") || !rawText.endsWith("END:VCARD")) {
      return null;
    }
    String[] names = matchVCardPrefixedField("FN", rawText);
    if (names == null) {
      // If no display names found, look for regular name fields and format them
      names = matchVCardPrefixedField("N", rawText);
      formatNames(names);
    }
    String[] phoneNumbers = matchVCardPrefixedField("TEL", rawText);
    String[] emails = matchVCardPrefixedField("EMAIL", rawText);
    String note = matchSingleVCardPrefixedField("NOTE", rawText);
    String address = matchSingleVCardPrefixedField("ADR", rawText);
    address = formatAddress(address);
    String org = matchSingleVCardPrefixedField("ORG", rawText);
    String birthday = matchSingleVCardPrefixedField("BDAY", rawText);
    if (birthday != null && !isStringOfDigits(birthday, 8)) {
      return null;
    }
    String title = matchSingleVCardPrefixedField("TITLE", rawText);
    return new AddressBookParsedResult(names, phoneNumbers, emails, note, address, org, birthday, title); 
  }

  private static String[] matchVCardPrefixedField(String prefix, String rawText) {
    Vector matches = null;
    int i = 0;
    int max = rawText.length();
    while (i < max) {
      i = rawText.indexOf(prefix, i);
      if (i < 0) {
        break;
      }
      if (i > 0 && rawText.charAt(i - 1) != '\n') {
        // then this didn't start a new token, we matched in the middle of something
        i++;
        continue;
      }
      i += prefix.length(); // Skip past this prefix we found to start
      if (rawText.charAt(i) != ':' && rawText.charAt(i) != ';') {
        continue;
      }
      while (rawText.charAt(i) != ':') { // Skip until a colon
        i++;
      }
      i++; // skip colon
      int start = i; // Found the start of a match here
      boolean done = false;
      while (!done) {
        i = rawText.indexOf((int) '\n', i); // Really, ends in \r\n
        if (i < 0) {
          // No terminating end character? uh, done. Set i such that loop terminates and break
          i = rawText.length();
          done = true;
        } else {
          // found a match
          if (matches == null) {
            matches = new Vector(3); // lazy init
          }
          matches.addElement(rawText.substring(start, i - 1)); // i - 1 to strip off the \r too
          i++;
          done = true;
        }
      }
    }
    if (matches == null || matches.isEmpty()) {
      return null;
    }
    return toStringArray(matches);
  }

  static String matchSingleVCardPrefixedField(String prefix, String rawText) {
    String[] values = matchVCardPrefixedField(prefix, rawText);
    return values == null ? null : values[0];
  }

  private static String formatAddress(String address) {
    if (address == null) {
      return null;
    }
    int length = address.length();
    StringBuffer newAddress = new StringBuffer(length);
    for (int j = 0; j < length; j++) {
      char c = address.charAt(j);
      if (c == ';') {
        newAddress.append(' ');
      } else {
        newAddress.append(c);
      }
    }
    return newAddress.toString().trim();
  }

  /**
   * Formats name fields of the form "Public;John;Q.;Reverend;III" into a form like
   * "Reverend John Q. Public III".
   *
   * @param names name values to format, in place
   */
  private static void formatNames(String[] names) {
    if (names != null) {
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        String[] components = new String[5];
        int start = 0;
        int end;
        int componentIndex = 0;
        while ((end = name.indexOf(';', start)) > 0) {
          components[componentIndex] = name.substring(start, end);
          componentIndex++;
          start = end + 1;
        }
        components[componentIndex] = name.substring(start);
        StringBuffer newName = new StringBuffer();
        maybeAppendComponent(components, 3, newName);
        maybeAppendComponent(components, 1, newName);
        maybeAppendComponent(components, 2, newName);
        maybeAppendComponent(components, 0, newName);
        maybeAppendComponent(components, 4, newName);
        names[i] = newName.toString().trim();
      }
    }
  }

  private static void maybeAppendComponent(String[] components, int i, StringBuffer newName) {
    if (components[i] != null) {
      newName.append(' ');
      newName.append(components[i]);
    }
  }

}