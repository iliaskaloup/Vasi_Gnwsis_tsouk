/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.filestructurefinder;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FileStructure;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.elasticsearch.xpack.ml.filestructurefinder.DelimitedFileStructureFinder.levenshteinFieldwiseCompareRows;
import static org.elasticsearch.xpack.ml.filestructurefinder.DelimitedFileStructureFinder.levenshteinDistance;
import static org.hamcrest.Matchers.arrayContaining;

public class DelimitedFileStructureFinderTests extends FileStructureTestCase {

    private FileStructureFinderFactory csvFactory = new DelimitedFileStructureFinderFactory(',', '"', 2, false);

    public void testCreateConfigsGivenCompleteCsv() throws Exception {
        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureOverrides.EMPTY_OVERRIDES);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertEquals("^\"?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("time", "message"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCompleteCsvAndColumnNamesOverride() throws Exception {

        FileStructureOverrides overrides = FileStructureOverrides.builder().setColumnNames(Arrays.asList("my_time", "my_message")).build();

        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker, overrides);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertEquals("^\"?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("my_time", "my_message"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("my_time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCompleteCsvAndHasHeaderRowOverride() throws Exception {

        // It's obvious the first row really should be a header row, so by overriding
        // detection with the wrong choice the results will be completely changed
        FileStructureOverrides overrides = FileStructureOverrides.builder().setHasHeaderRow(false).build();

        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker, overrides);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertNull(structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertFalse(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("column1", "column2"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertNull(structure.getTimestampField());
        assertNull(structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithIncompleteLastRecord() throws Exception {
        String sample = "message,time,count\n" +
            "\"hello\n" +
            "world\",2018-05-17T13:41:23,1\n" +
            "\"hello again\n"; // note that this last record is truncated
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureOverrides.EMPTY_OVERRIDES);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?message\"?,\"?time\"?,\"?count\"?", structure.getExcludeLinesPattern());
        assertEquals("^.*?,\"?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("message", "time", "count"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNulls() throws Exception {
        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount,,\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureOverrides.EMPTY_OVERRIDES);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
            "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
            "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?,\"?\"?,\"?\"?",
            structure.getExcludeLinesPattern());
        assertEquals("^.*?,\"?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount", "column18", "column19"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsAndOverriddenTimeField() throws Exception {

        // Default timestamp field is the first field from the start of each row that contains a
        // consistent timestamp format, so if we want the second we need an override
        FileStructureOverrides overrides = FileStructureOverrides.builder().setTimestampField("tpep_dropoff_datetime").build();

        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount,,\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker, overrides);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
            "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
            "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?,\"?\"?,\"?\"?",
            structure.getExcludeLinesPattern());
        assertEquals("^.*?,.*?,\"?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount", "column18", "column19"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_dropoff_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsExceptHeader() throws Exception {
        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureOverrides.EMPTY_OVERRIDES);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
                "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
                "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?",
            structure.getExcludeLinesPattern());
        assertEquals("^.*?,\"?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsExceptHeaderAndColumnNamesOverride() throws Exception {

        FileStructureOverrides overrides = FileStructureOverrides.builder()
            .setColumnNames(Arrays.asList("my_VendorID", "my_tpep_pickup_datetime", "my_tpep_dropoff_datetime", "my_passenger_count",
                "my_trip_distance", "my_RatecodeID", "my_store_and_fwd_flag", "my_PULocationID", "my_DOLocationID", "my_payment_type",
                "my_fare_amount", "my_extra", "my_mta_tax", "my_tip_amount", "my_tolls_amount", "my_improvement_surcharge",
                "my_total_amount")).build();

        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker, overrides);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
                "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
                "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?",
            structure.getExcludeLinesPattern());
        assertEquals("^.*?,\"?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("my_VendorID", "my_tpep_pickup_datetime", "my_tpep_dropoff_datetime", "my_passenger_count",
            "my_trip_distance", "my_RatecodeID", "my_store_and_fwd_flag", "my_PULocationID", "my_DOLocationID", "my_payment_type",
            "my_fare_amount", "my_extra", "my_mta_tax", "my_tip_amount", "my_tolls_amount", "my_improvement_surcharge", "my_total_amount"),
            structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("my_tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTimeLastColumn() throws Exception {
        String sample = "\"pos_id\",\"trip_id\",\"latitude\",\"longitude\",\"altitude\",\"timestamp\"\n" +
            "\"1\",\"3\",\"4703.7815\",\"1527.4713\",\"359.9\",\"2017-01-19 16:19:04.742113\"\n" +
            "\"2\",\"3\",\"4703.7815\",\"1527.4714\",\"359.9\",\"2017-01-19 16:19:05.741890\"\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureOverrides.EMPTY_OVERRIDES);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?pos_id\"?,\"?trip_id\"?,\"?latitude\"?,\"?longitude\"?,\"?altitude\"?,\"?timestamp\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("pos_id", "trip_id", "latitude", "longitude", "altitude", "timestamp"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("timestamp", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss.SSSSSS"), structure.getTimestampFormats());
    }

    public void testFindHeaderFromSampleGivenHeaderInSample() throws IOException {
        String withHeader = "time,airline,responsetime,sourcetype\n" +
            "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = DelimitedFileStructureFinder.findHeaderFromSample(explanation,
            DelimitedFileStructureFinder.readRows(withHeader, CsvPreference.EXCEL_PREFERENCE).v1(), FileStructureOverrides.EMPTY_OVERRIDES);

        assertTrue(header.v1());
        assertThat(header.v2(), arrayContaining("time", "airline", "responsetime", "sourcetype"));
    }

    public void testFindHeaderFromSampleGivenHeaderNotInSample() throws IOException {
        String withoutHeader = "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = DelimitedFileStructureFinder.findHeaderFromSample(explanation,
            DelimitedFileStructureFinder.readRows(withoutHeader, CsvPreference.EXCEL_PREFERENCE).v1(),
            FileStructureOverrides.EMPTY_OVERRIDES);

        assertFalse(header.v1());
        assertThat(header.v2(), arrayContaining("", "", "", ""));
    }

    public void testLevenshteinDistance() {

        assertEquals(0, levenshteinDistance("cat", "cat"));
        assertEquals(3, levenshteinDistance("cat", "dog"));
        assertEquals(5, levenshteinDistance("cat", "mouse"));
        assertEquals(3, levenshteinDistance("cat", ""));

        assertEquals(3, levenshteinDistance("dog", "cat"));
        assertEquals(0, levenshteinDistance("dog", "dog"));
        assertEquals(4, levenshteinDistance("dog", "mouse"));
        assertEquals(3, levenshteinDistance("dog", ""));

        assertEquals(5, levenshteinDistance("mouse", "cat"));
        assertEquals(4, levenshteinDistance("mouse", "dog"));
        assertEquals(0, levenshteinDistance("mouse", "mouse"));
        assertEquals(5, levenshteinDistance("mouse", ""));

        assertEquals(3, levenshteinDistance("", "cat"));
        assertEquals(3, levenshteinDistance("", "dog"));
        assertEquals(5, levenshteinDistance("", "mouse"));
        assertEquals(0, levenshteinDistance("", ""));
    }

    public void testLevenshteinCompareRows() {

        assertEquals(0, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "dog")));
        assertEquals(0, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "cat")));
        assertEquals(3, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("dog", "cat")));
        assertEquals(3, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("mouse", "cat")));
        assertEquals(5, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "dog", "cat")));
        assertEquals(4, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "mouse", "mouse")));
        assertEquals(7, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "cat", "dog")));
    }

    public void testLineHasUnescapedQuote() {

        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,b,c\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,\"b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,\"c\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,\"b\"\"\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,\"c\"\"\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"\"\"a\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\"\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,\"\"b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("between\"words,b,c", CsvPreference.EXCEL_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("x and \"y\",b,c", CsvPreference.EXCEL_PREFERENCE));

        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\tb\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\tb\tc\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\t\"b\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\t\"c\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\t\"b\"\"\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\t\"c\"\"\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"\"\"a\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\"\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\t\"\"b\"\tc", CsvPreference.TAB_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("between\"words\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("x and \"y\"\tb\tc", CsvPreference.TAB_PREFERENCE));
    }

    public void testRowContainsDuplicateNonEmptyValues() {

        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Collections.singletonList("a")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Collections.singletonList("")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "c")));
        assertEquals("a", DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "a")));
        assertEquals("b", DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "b")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "", "")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("", "a", "")));
    }
}