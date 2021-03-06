/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.filestructurefinder;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.grok.Grok;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Used to find the best timestamp format for one of the following situations:
 * 1. Matching an entire field value
 * 2. Matching a timestamp found somewhere within a message
 */
public final class TimestampFormatFinder {

    private static final String PREFACE = "preface";
    private static final String EPILOGUE = "epilogue";

    private static final Pattern FRACTIONAL_SECOND_INTERPRETER = Pattern.compile("([:.,])(\\d{3,9})");
    private static final char DEFAULT_FRACTIONAL_SECOND_SEPARATOR = ',';

    /**
     * The timestamp patterns are complex and it can be slow to prove they do not
     * match anywhere in a long message.  Many of the timestamps are similar and
     * will never be found in a string if simpler sub-patterns do not exist in the
     * string.  These sub-patterns can be used to quickly rule out multiple complex
     * patterns.  These patterns do not need to represent quantities that are
     * useful to know the value of, merely character sequences that can be used to
     * prove that <em>several</em> more complex patterns cannot possibly match.
     */
    private static final List<Pattern> QUICK_RULE_OUT_PATTERNS = Arrays.asList(
        // YYYY-MM-dd followed by a space
        Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2} "),
        // The end of some number (likely year or day) followed by a space then HH:mm
        Pattern.compile("\\d \\d{2}:\\d{2}\\b"),
        // HH:mm:ss surrounded by spaces
        Pattern.compile(" \\d{2}:\\d{2}:\\d{2} ")
    );

    /**
     * The first match in this list will be chosen, so it needs to be ordered
     * such that more generic patterns come after more specific patterns.
     */
    static final List<CandidateTimestampFormat> ORDERED_CANDIDATE_FORMATS = Arrays.asList(
        // The TOMCAT_DATESTAMP format has to come before ISO8601 because it's basically ISO8601 but
        // with a space before the timezone, and because the timezone is optional in ISO8601 it will
        // be recognised as that with the timezone missed off if ISO8601 is checked first
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ss,SSS Z", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}",
            "\\b20\\d{2}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)[:.,][0-9]{3,9} (?:Z|[+-]%{HOUR}%{MINUTE})\\b",
            "TOMCAT_DATESTAMP", Arrays.asList(0, 1)),
        // The Elasticsearch ISO8601 parser requires a literal T between the date and time, so
        // longhand formats are needed if there's a space instead
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ss,SSSZ", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)[:.,][0-9]{3,9}(?:Z|[+-]%{HOUR}%{MINUTE})\\b",
            "TIMESTAMP_ISO8601", Arrays.asList(0, 1)),
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ss,SSSZZ", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)[:.,][0-9]{3,9}[+-]%{HOUR}:%{MINUTE}\\b",
            "TIMESTAMP_ISO8601", Arrays.asList(0, 1)),
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ss,SSS", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)[:.,][0-9]{3,9}\\b", "TIMESTAMP_ISO8601",
            Arrays.asList(0, 1)),
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ssZ", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)(?:Z|[+-]%{HOUR}%{MINUTE})\\b", "TIMESTAMP_ISO8601",
            Arrays.asList(0, 1)),
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ssZZ", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)[+-]%{HOUR}:%{MINUTE}\\b", "TIMESTAMP_ISO8601",
            Arrays.asList(0, 1)),
        new CandidateTimestampFormat("YYYY-MM-dd HH:mm:ss", "\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
            "\\b%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{HOUR}:?%{MINUTE}:(?:[0-5][0-9]|60)\\b", "TIMESTAMP_ISO8601",
            Arrays.asList(0, 1)),
        new CandidateTimestampFormat("ISO8601", "\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", "\\b%{TIMESTAMP_ISO8601}\\b",
            "TIMESTAMP_ISO8601"),
        new CandidateTimestampFormat("EEE MMM dd YYYY HH:mm:ss zzz",
            "\\b[A-Z]\\S{2,8} [A-Z]\\S{2,8} \\d{1,2} \\d{4} \\d{2}:\\d{2}:\\d{2} ",
            "\\b%{DAY} %{MONTH} %{MONTHDAY} %{YEAR} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) %{TZ}\\b", "DATESTAMP_RFC822", Arrays.asList(1, 2)),
        new CandidateTimestampFormat("EEE MMM dd YYYY HH:mm zzz", "\\b[A-Z]\\S{2,8} [A-Z]\\S{2,8} \\d{1,2} \\d{4} \\d{2}:\\d{2} ",
            "\\b%{DAY} %{MONTH} %{MONTHDAY} %{YEAR} %{HOUR}:%{MINUTE} %{TZ}\\b", "DATESTAMP_RFC822", Collections.singletonList(1)),
        new CandidateTimestampFormat("EEE, dd MMM YYYY HH:mm:ss ZZ",
            "\\b[A-Z]\\S{2,8}, \\d{1,2} [A-Z]\\S{2,8} \\d{4} \\d{2}:\\d{2}:\\d{2} ",
            "\\b%{DAY}, %{MONTHDAY} %{MONTH} %{YEAR} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) (?:Z|[+-]%{HOUR}:%{MINUTE})\\b",
            "DATESTAMP_RFC2822", Arrays.asList(1, 2)),
        new CandidateTimestampFormat("EEE, dd MMM YYYY HH:mm:ss Z",
            "\\b[A-Z]\\S{2,8}, \\d{1,2} [A-Z]\\S{2,8} \\d{4} \\d{2}:\\d{2}:\\d{2} ",
            "\\b%{DAY}, %{MONTHDAY} %{MONTH} %{YEAR} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) (?:Z|[+-]%{HOUR}%{MINUTE})\\b",
            "DATESTAMP_RFC2822", Arrays.asList(1, 2)),
        new CandidateTimestampFormat("EEE, dd MMM YYYY HH:mm ZZ", "\\b[A-Z]\\S{2,8}, \\d{1,2} [A-Z]\\S{2,8} \\d{4} \\d{2}:\\d{2} ",
            "\\b%{DAY}, %{MONTHDAY} %{MONTH} %{YEAR} %{HOUR}:%{MINUTE} (?:Z|[+-]%{HOUR}:%{MINUTE})\\b", "DATESTAMP_RFC2822",
            Collections.singletonList(1)),
        new CandidateTimestampFormat("EEE, dd MMM YYYY HH:mm Z", "\\b[A-Z]\\S{2,8}, \\d{1,2} [A-Z]\\S{2,8} \\d{4} \\d{2}:\\d{2} ",
            "\\b%{DAY}, %{MONTHDAY} %{MONTH} %{YEAR} %{HOUR}:%{MINUTE} (?:Z|[+-]%{HOUR}%{MINUTE})\\b", "DATESTAMP_RFC2822",
            Collections.singletonList(1)),
        new CandidateTimestampFormat("EEE MMM dd HH:mm:ss zzz YYYY",
            "\\b[A-Z]\\S{2,8} [A-Z]\\S{2,8} \\d{1,2} \\d{2}:\\d{2}:\\d{2} [A-Z]{3,4} \\d{4}\\b",
            "\\b%{DAY} %{MONTH} %{MONTHDAY} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) %{TZ} %{YEAR}\\b", "DATESTAMP_OTHER",
            Arrays.asList(1, 2)),
        new CandidateTimestampFormat("EEE MMM dd HH:mm zzz YYYY",
            "\\b[A-Z]\\S{2,8} [A-Z]\\S{2,8} \\d{1,2} \\d{2}:\\d{2} [A-Z]{3,4} \\d{4}\\b",
            "\\b%{DAY} %{MONTH} %{MONTHDAY} %{HOUR}:%{MINUTE} %{TZ} %{YEAR}\\b", "DATESTAMP_OTHER", Collections.singletonList(1)),
        new CandidateTimestampFormat("YYYYMMddHHmmss", "\\b\\d{14}\\b",
            "\\b20\\d{2}%{MONTHNUM2}(?:(?:0[1-9])|(?:[12][0-9])|(?:3[01]))(?:2[0123]|[01][0-9])%{MINUTE}(?:[0-5][0-9]|60)\\b",
            "DATESTAMP_EVENTLOG"),
        new CandidateTimestampFormat("EEE MMM dd HH:mm:ss YYYY",
            "\\b[A-Z]\\S{2,8} [A-Z]\\S{2,8} \\d{1,2} \\d{2}:\\d{2}:\\d{2} \\d{4}\\b",
            "\\b%{DAY} %{MONTH} %{MONTHDAY} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) %{YEAR}\\b", "HTTPDERROR_DATE", Arrays.asList(1, 2)),
        new CandidateTimestampFormat(Arrays.asList("MMM dd HH:mm:ss,SSS", "MMM  d HH:mm:ss,SSS"),
            "\\b[A-Z]\\S{2,8} {1,2}\\d{1,2} \\d{2}:\\d{2}:\\d{2},\\d{3}",
            "%{MONTH} +%{MONTHDAY} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60)[:.,][0-9]{3,9}\\b", "SYSLOGTIMESTAMP",
            Collections.singletonList(1)),
        new CandidateTimestampFormat(Arrays.asList("MMM dd HH:mm:ss", "MMM  d HH:mm:ss"),
            "\\b[A-Z]\\S{2,8} {1,2}\\d{1,2} \\d{2}:\\d{2}:\\d{2}\\b", "%{MONTH} +%{MONTHDAY} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60)\\b",
            "SYSLOGTIMESTAMP", Collections.singletonList(1)),
        new CandidateTimestampFormat("dd/MMM/YYYY:HH:mm:ss Z", "\\b\\d{2}/[A-Z]\\S{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} ",
            "\\b%{MONTHDAY}/%{MONTH}/%{YEAR}:%{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) [+-]?%{HOUR}%{MINUTE}\\b", "HTTPDATE"),
        new CandidateTimestampFormat("MMM dd, YYYY K:mm:ss a", "\\b[A-Z]\\S{2,8} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M\\b",
            "%{MONTH} %{MONTHDAY}, 20\\d{2} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60) (?:AM|PM)\\b", "CATALINA_DATESTAMP"),
        new CandidateTimestampFormat(Arrays.asList("MMM dd YYYY HH:mm:ss", "MMM  d YYYY HH:mm:ss"),
            "\\b[A-Z]\\S{2,8} {1,2}\\d{1,2} \\d{4} \\d{2}:\\d{2}:\\d{2}\\b",
            "%{MONTH} +%{MONTHDAY} %{YEAR} %{HOUR}:%{MINUTE}:(?:[0-5][0-9]|60)\\b", "CISCOTIMESTAMP", Collections.singletonList(1)),
        new CandidateTimestampFormat("UNIX_MS", "\\b\\d{13}\\b", "\\b\\d{13}\\b", "POSINT"),
        new CandidateTimestampFormat("UNIX", "\\b\\d{10}\\.\\d{3,9}\\b", "\\b\\d{10}\\.(?:\\d{3}){1,3}\\b", "NUMBER"),
        new CandidateTimestampFormat("UNIX", "\\b\\d{10}\\b", "\\b\\d{10}\\b", "POSINT"),
        new CandidateTimestampFormat("TAI64N", "\\b[0-9A-Fa-f]{24}\\b", "\\b[0-9A-Fa-f]{24}\\b", "BASE16NUM")
    );

    private TimestampFormatFinder() {
    }

    /**
     * Find the first timestamp format that matches part of the supplied value.
     * @param text The value that the returned timestamp format must exist within.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstMatch(String text) {
        return findFirstMatch(text, 0);
    }

    /**
     * Find the first timestamp format that matches part of the supplied value.
     * @param text The value that the returned timestamp format must exist within.
     * @param requiredFormat A date format that any returned match must support.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstMatch(String text, String requiredFormat) {
        return findFirstMatch(text, 0, requiredFormat);
    }

    /**
     * Find the first timestamp format that matches part of the supplied value,
     * excluding a specified number of candidate formats.
     * @param text The value that the returned timestamp format must exist within.
     * @param ignoreCandidates The number of candidate formats to exclude from the search.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstMatch(String text, int ignoreCandidates) {
        return findFirstMatch(text, ignoreCandidates, null);
    }

    /**
     * Find the first timestamp format that matches part of the supplied value,
     * excluding a specified number of candidate formats.
     * @param text             The value that the returned timestamp format must exist within.
     * @param ignoreCandidates The number of candidate formats to exclude from the search.
     * @param requiredFormat A date format that any returned match must support.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstMatch(String text, int ignoreCandidates, String requiredFormat) {
        Boolean[] quickRuleoutMatches = new Boolean[QUICK_RULE_OUT_PATTERNS.size()];
        int index = ignoreCandidates;
        for (CandidateTimestampFormat candidate : ORDERED_CANDIDATE_FORMATS.subList(ignoreCandidates, ORDERED_CANDIDATE_FORMATS.size())) {
            if (requiredFormat == null || candidate.dateFormats.contains(requiredFormat)) {
                boolean quicklyRuledOut = false;
                for (Integer quickRuleOutIndex : candidate.quickRuleOutIndices) {
                    if (quickRuleoutMatches[quickRuleOutIndex] == null) {
                        quickRuleoutMatches[quickRuleOutIndex] = QUICK_RULE_OUT_PATTERNS.get(quickRuleOutIndex).matcher(text).find();
                    }
                    if (quickRuleoutMatches[quickRuleOutIndex] == false) {
                        quicklyRuledOut = true;
                        break;
                    }
                }
                if (quicklyRuledOut == false) {
                    Map<String, Object> captures = candidate.strictSearchGrok.captures(text);
                    if (captures != null) {
                        String preface = captures.getOrDefault(PREFACE, "").toString();
                        String epilogue = captures.getOrDefault(EPILOGUE, "").toString();
                        return makeTimestampMatch(candidate, index, preface, text.substring(preface.length(),
                            text.length() - epilogue.length()), epilogue);
                    }
                }
            }
            ++index;
        }
        return null;
    }

    /**
     * Find the best timestamp format for matching an entire field value.
     * @param text The value that the returned timestamp format must match in its entirety.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstFullMatch(String text) {
        return findFirstFullMatch(text, 0);
    }

    /**
     * Find the best timestamp format for matching an entire field value.
     * @param text The value that the returned timestamp format must match in its entirety.
     * @param requiredFormat A date format that any returned match must support.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstFullMatch(String text, String requiredFormat) {
        return findFirstFullMatch(text, 0, requiredFormat);
    }

    /**
     * Find the best timestamp format for matching an entire field value,
     * excluding a specified number of candidate formats.
     * @param text The value that the returned timestamp format must match in its entirety.
     * @param ignoreCandidates The number of candidate formats to exclude from the search.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstFullMatch(String text, int ignoreCandidates) {
        return findFirstFullMatch(text, ignoreCandidates, null);
    }

    /**
     * Find the best timestamp format for matching an entire field value,
     * excluding a specified number of candidate formats.
     * @param text The value that the returned timestamp format must match in its entirety.
     * @param ignoreCandidates The number of candidate formats to exclude from the search.
     * @param requiredFormat A date format that any returned match must support.
     * @return The timestamp format, or <code>null</code> if none matches.
     */
    public static TimestampMatch findFirstFullMatch(String text, int ignoreCandidates, String requiredFormat) {
        int index = ignoreCandidates;
        for (CandidateTimestampFormat candidate : ORDERED_CANDIDATE_FORMATS.subList(ignoreCandidates, ORDERED_CANDIDATE_FORMATS.size())) {
            if (requiredFormat == null || candidate.dateFormats.contains(requiredFormat)) {
                Map<String, Object> captures = candidate.strictFullMatchGrok.captures(text);
                if (captures != null) {
                    return makeTimestampMatch(candidate, index, "", text, "");
                }
            }
            ++index;
        }
        return null;
    }

    private static TimestampMatch makeTimestampMatch(CandidateTimestampFormat chosenTimestampFormat, int chosenIndex,
                                                     String preface, String matchedDate, String epilogue) {
        Tuple<Character, Integer> fractionalSecondsInterpretation = interpretFractionalSeconds(matchedDate);
        List<String> dateFormats = chosenTimestampFormat.dateFormats;
        Pattern simplePattern = chosenTimestampFormat.simplePattern;
        char separator = fractionalSecondsInterpretation.v1();
        if (separator != DEFAULT_FRACTIONAL_SECOND_SEPARATOR) {
            dateFormats = dateFormats.stream().map(dateFormat -> dateFormat.replace(DEFAULT_FRACTIONAL_SECOND_SEPARATOR, separator))
                .collect(Collectors.toList());
            if (dateFormats.stream().noneMatch(dateFormat -> dateFormat.startsWith("UNIX"))) {
                String patternStr = simplePattern.pattern();
                int separatorPos = patternStr.lastIndexOf(DEFAULT_FRACTIONAL_SECOND_SEPARATOR);
                if (separatorPos >= 0) {
                    StringBuilder newPatternStr = new StringBuilder(patternStr);
                    newPatternStr.replace(separatorPos, separatorPos + 1, ((separator == '.') ? "\\" : "") + separator);
                    simplePattern = Pattern.compile(newPatternStr.toString());
                }
            }
        }
        int numberOfDigitsInFractionalComponent = fractionalSecondsInterpretation.v2();
        if (numberOfDigitsInFractionalComponent > 3) {
            String fractionalSecondsFormat = "SSSSSSSSS".substring(0, numberOfDigitsInFractionalComponent);
            dateFormats = dateFormats.stream().map(dateFormat -> dateFormat.replace("SSS", fractionalSecondsFormat))
                .collect(Collectors.toList());
        }
        return new TimestampMatch(chosenIndex, preface, dateFormats, simplePattern, chosenTimestampFormat.standardGrokPatternName,
            epilogue);
    }

    /**
     * Interpret the fractional seconds component of a date to determine two things:
     * 1. The separator character - one of colon, comma and dot.
     * 2. The number of digits in the fractional component.
     * @param date The textual representation of the date for which fractional seconds are to be interpreted.
     * @return A tuple of (fractional second separator character, number of digits in fractional component).
     */
    static Tuple<Character, Integer> interpretFractionalSeconds(String date) {

        Matcher matcher = FRACTIONAL_SECOND_INTERPRETER.matcher(date);
        if (matcher.find()) {
            return new Tuple<>(matcher.group(1).charAt(0), matcher.group(2).length());
        }

        return new Tuple<>(DEFAULT_FRACTIONAL_SECOND_SEPARATOR, 0);
    }

    /**
     * Represents a timestamp that has matched a field value or been found within a message.
     */
    public static final class TimestampMatch {

        /**
         * The index of the corresponding entry in the <code>ORDERED_CANDIDATE_FORMATS</code> list.
         */
        public final int candidateIndex;

        /**
         * Text that came before the timestamp in the matched field/message.
         */
        public final String preface;

        /**
         * Time format specifier(s) that will work with Logstash and Ingest pipeline date parsers.
         */
        public final List<String> dateFormats;

        /**
         * A simple regex that will work in many languages to detect whether the timestamp format
         * exists in a particular line.
         */
        public final Pattern simplePattern;

        /**
         * Name of an out-of-the-box Grok pattern that will match the timestamp.
         */
        public final String grokPatternName;

        /**
         * Text that came after the timestamp in the matched field/message.
         */
        public final String epilogue;

        TimestampMatch(int candidateIndex, String preface, String dateFormat, String simpleRegex, String grokPatternName, String epilogue) {
            this(candidateIndex, preface, Collections.singletonList(dateFormat), simpleRegex, grokPatternName, epilogue);
        }

        TimestampMatch(int candidateIndex, String preface, String dateFormat, String simpleRegex, String grokPatternName, String epilogue,
                       boolean hasFractionalComponentSmallerThanMillisecond) {
            this(candidateIndex, preface, Collections.singletonList(dateFormat), simpleRegex, grokPatternName, epilogue);
        }

        TimestampMatch(int candidateIndex, String preface, List<String> dateFormats, String simpleRegex, String grokPatternName,
                       String epilogue) {
            this(candidateIndex, preface, dateFormats, Pattern.compile(simpleRegex), grokPatternName, epilogue);
        }

        TimestampMatch(int candidateIndex, String preface, List<String> dateFormats, Pattern simplePattern, String grokPatternName,
                       String epilogue) {
            this.candidateIndex = candidateIndex;
            this.preface = preface;
            this.dateFormats = dateFormats;
            this.simplePattern = simplePattern;
            this.grokPatternName = grokPatternName;
            this.epilogue = epilogue;
        }

        /**
         * Does the parsing the timestamp produce different results depending on the timezone of the parser?
         * I.e., does the textual representation NOT define the timezone?
         */
        public boolean hasTimezoneDependentParsing() {
            return dateFormats.stream()
                .anyMatch(dateFormat -> dateFormat.contains("HH") && dateFormat.toLowerCase(Locale.ROOT).indexOf('z') == -1);
        }

        /**
         * Sometimes Elasticsearch mappings for dates need to include the format.
         * This method returns appropriate mappings settings: at minimum "type"="date",
         * and possibly also a "format" setting.
         */
        public Map<String, String> getEsDateMappingTypeWithFormat() {
            if (dateFormats.contains("TAI64N")) {
                // There's no format for TAI64N in the date formats used in mappings
                return Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "keyword");
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            mapping.put(FileStructureUtils.MAPPING_TYPE_SETTING, "date");
            String formats = dateFormats.stream().flatMap(format -> {
                switch (format) {
                    case "ISO8601":
                        return Stream.empty();
                    case "UNIX_MS":
                        return Stream.of("epoch_millis");
                    case "UNIX":
                        return Stream.of("epoch_second");
                    default:
                        return Stream.of(format);
                }
            }).collect(Collectors.joining("||"));
            if (formats.isEmpty() == false) {
                mapping.put(FileStructureUtils.MAPPING_FORMAT_SETTING, formats);
            }
            return mapping;
        }

        @Override
        public int hashCode() {
            return Objects.hash(candidateIndex, preface, dateFormats, simplePattern.pattern(), grokPatternName, epilogue);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }

            TimestampMatch that = (TimestampMatch) other;
            return this.candidateIndex == that.candidateIndex &&
                Objects.equals(this.preface, that.preface) &&
                Objects.equals(this.dateFormats, that.dateFormats) &&
                Objects.equals(this.simplePattern.pattern(), that.simplePattern.pattern()) &&
                Objects.equals(this.grokPatternName, that.grokPatternName) &&
                Objects.equals(this.epilogue, that.epilogue);
        }

        @Override
        public String toString() {
            return "index = " + candidateIndex + (preface.isEmpty() ? "" : ", preface = '" + preface + "'") +
                ", date formats = " + dateFormats.stream().collect(Collectors.joining("', '", "[ '", "' ]")) +
                ", simple pattern = '" + simplePattern.pattern() + "', grok pattern = '" + grokPatternName + "'" +
                (epilogue.isEmpty() ? "" : ", epilogue = '" + epilogue + "'");
        }
    }

    static final class CandidateTimestampFormat {

        final List<String> dateFormats;
        final Pattern simplePattern;
        final Grok strictSearchGrok;
        final Grok strictFullMatchGrok;
        final String standardGrokPatternName;
        final List<Integer> quickRuleOutIndices;

        CandidateTimestampFormat(String dateFormat, String simpleRegex, String strictGrokPattern, String standardGrokPatternName) {
            this(Collections.singletonList(dateFormat), simpleRegex, strictGrokPattern, standardGrokPatternName);
        }

        CandidateTimestampFormat(String dateFormat, String simpleRegex, String strictGrokPattern, String standardGrokPatternName,
                                 List<Integer> quickRuleOutIndices) {
            this(Collections.singletonList(dateFormat), simpleRegex, strictGrokPattern, standardGrokPatternName, quickRuleOutIndices);
        }

        CandidateTimestampFormat(List<String> dateFormats, String simpleRegex, String strictGrokPattern, String standardGrokPatternName) {
            this(dateFormats, simpleRegex, strictGrokPattern, standardGrokPatternName, Collections.emptyList());
        }

        CandidateTimestampFormat(List<String> dateFormats, String simpleRegex, String strictGrokPattern, String standardGrokPatternName,
                                 List<Integer> quickRuleOutIndices) {
            this.dateFormats = dateFormats;
            this.simplePattern = Pattern.compile(simpleRegex, Pattern.MULTILINE);
            // The (?m) here has the Ruby meaning, which is equivalent to (?s) in Java
            this.strictSearchGrok = new Grok(Grok.getBuiltinPatterns(), "(?m)%{DATA:" + PREFACE + "}" + strictGrokPattern +
                "%{GREEDYDATA:" + EPILOGUE + "}");
            this.strictFullMatchGrok = new Grok(Grok.getBuiltinPatterns(), "^" + strictGrokPattern + "$");
            this.standardGrokPatternName = standardGrokPatternName;
            assert quickRuleOutIndices.stream()
                .noneMatch(quickRuleOutIndex -> quickRuleOutIndex < 0 || quickRuleOutIndex >= QUICK_RULE_OUT_PATTERNS.size());
            this.quickRuleOutIndices = quickRuleOutIndices;
        }
    }
}