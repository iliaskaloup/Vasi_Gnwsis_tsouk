/*
 * Copyright 2007 ZXing authors
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
 * Implements the "MECARD" address book entry format.
 *
 * Supported keys: N, TEL, EMAIL, NOTE, ADR Unsupported keys: SOUND, TEL-AV, BDAY, URL, NICKNAME
 *
 * Except for TEL, multiple values for keys are also not supported;
 * the first one found takes precedence.
 *
 * @author srowen@google.com (Sean Owen)
 */
final class AddressBookDoCoMoResultParser extends AbstractDoCoMoResultParser {

  public static AddressBookParsedResult parse(Result result) {
    String rawText = result.getText();
    if (rawText == null || !rawText.startsWith("MECARD:")) {
      return null;
    }
    String[] rawName = matchDoCoMoPrefixedField("N:", rawText);
    if (rawName == null) {
      return null;
    }
    String name = parseName(rawName[0]);
    String[] phoneNumbers = matchDoCoMoPrefixedField("TEL:", rawText);
    String email = matchSingleDoCoMoPrefixedField("EMAIL:", rawText);
    String note = matchSingleDoCoMoPrefixedField("NOTE:", rawText);
    String address = matchSingleDoCoMoPrefixedField("ADR:", rawText);
    String birthday = matchSingleDoCoMoPrefixedField("BDAY:", rawText);
    if (birthday != null && !isStringOfDigits(birthday, 8)) {
      return null;
    }
    return new AddressBookParsedResult(maybeWrap(name),
                                       phoneNumbers,
                                       maybeWrap(email),
                                       note,
                                       address,
                                       null,
                                       birthday,
                                       null);
  }

  private static String parseName(String name) {
    int comma = name.indexOf((int) ',');
    if (comma >= 0) {
      // Format may be last,first; switch it around
      return name.substring(comma + 1) + ' ' + name.substring(0, comma);
    }
    return name;
  }

}