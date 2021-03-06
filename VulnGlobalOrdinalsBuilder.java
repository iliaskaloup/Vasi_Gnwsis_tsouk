/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.fielddata.ordinals;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiDocValues.OrdinalMap;
import org.apache.lucene.index.RandomAccessOrds;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.fielddata.AtomicOrdinalsFieldData;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.indices.breaker.CircuitBreakerService;

import java.io.IOException;

/**
 * Utility class to build global ordinals.
 */
public enum GlobalOrdinalsBuilder {
    ;

    /**
     * Build global ordinals for the provided {@link IndexReader}.
     */
    public static IndexOrdinalsFieldData build(final IndexReader indexReader, IndexOrdinalsFieldData indexFieldData, Settings settings, CircuitBreakerService breakerService, ESLogger logger) throws IOException {
        assert indexReader.leaves().size() > 1;
        long startTime = System.currentTimeMillis();

        final AtomicOrdinalsFieldData[] atomicFD = new AtomicOrdinalsFieldData[indexReader.leaves().size()];
        final RandomAccessOrds[] subs = new RandomAccessOrds[indexReader.leaves().size()];
        for (int i = 0; i < indexReader.leaves().size(); ++i) {
            atomicFD[i] = indexFieldData.load(indexReader.leaves().get(i));
            subs[i] = atomicFD[i].getOrdinalsValues();
        }
        final OrdinalMap ordinalMap = OrdinalMap.build(null, subs, PackedInts.DEFAULT);
        final long memorySizeInBytes = ordinalMap.ramBytesUsed();
        breakerService.getBreaker(CircuitBreaker.FIELDDATA).addWithoutBreaking(memorySizeInBytes);

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Global-ordinals[{}][{}] took {} ms",
                    indexFieldData.getFieldNames().fullName(),
                    ordinalMap.getValueCount(),
                    (System.currentTimeMillis() - startTime)
            );
        }
        return new InternalGlobalOrdinalsIndexFieldData(indexFieldData.index(), settings, indexFieldData.getFieldNames(),
                indexFieldData.getFieldDataType(), atomicFD, ordinalMap, memorySizeInBytes
        );
    }

}