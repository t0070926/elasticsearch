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
package org.elasticsearch.search.aggregations.support;

import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.RangeFieldMapper;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.AggregationExecutionException;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@link ValuesSourceRegistry} holds the mapping from {@link ValuesSourceType}s to {@link AggregatorSupplier}s.  DO NOT directly
 * instantiate this class, instead get an already-configured copy from {@link QueryShardContext#getValuesSourceRegistry()}, or (in the case
 * of some test scenarios only) directly from {@link SearchModule#getValuesSourceRegistry()}
 *
 */
public class ValuesSourceRegistry {
    public static class Builder {
        private Map<String, List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>>> aggregatorRegistry = new HashMap<>();
        /**
         * Register a ValuesSource to Aggregator mapping.
         *
         * @param aggregationName The name of the family of aggregations, typically found via
         *                        {@link ValuesSourceAggregationBuilder#getType()}
         * @param appliesTo A predicate which accepts the resolved {@link ValuesSourceType} and decides if the given aggregator can be
         *                  applied to that type.
         * @param aggregatorSupplier An Aggregation-specific specialization of AggregatorSupplier which will construct the mapped aggregator
         */
        private void register(String aggregationName, Predicate<ValuesSourceType> appliesTo,
                                          AggregatorSupplier aggregatorSupplier) {
            if (aggregatorRegistry.containsKey(aggregationName) == false) {
                aggregatorRegistry.put(aggregationName, new ArrayList<>());
            }
            aggregatorRegistry.get(aggregationName).add( new AbstractMap.SimpleEntry<>(appliesTo, aggregatorSupplier));
        }

        /**
         * Register a ValuesSource to Aggregator mapping.  This version provides a convenience method for mappings that only apply to a
         * single {@link ValuesSourceType}, to allow passing in the type and auto-wrapping it in a predicate
         * @param aggregationName The name of the family of aggregations, typically found via
         *                        {@link ValuesSourceAggregationBuilder#getType()}
         * @param valuesSourceType The ValuesSourceType this mapping applies to.
         * @param aggregatorSupplier An Aggregation-specific specialization of AggregatorSupplier which will construct the mapped aggregator
         *                           from the aggregation standard set of parameters
         */
        public void register(String aggregationName, ValuesSourceType valuesSourceType, AggregatorSupplier aggregatorSupplier) {
            register(aggregationName, (candidate) -> valuesSourceType.equals(candidate), aggregatorSupplier);
        }

        /**
         * Register a ValuesSource to Aggregator mapping.  This version provides a convenience method for mappings that only apply to a
         * known list of {@link ValuesSourceType}, to allow passing in the type and auto-wrapping it in a predicate
         *  @param aggregationName The name of the family of aggregations, typically found via
         *                         {@link ValuesSourceAggregationBuilder#getType()}
         * @param valuesSourceTypes The ValuesSourceTypes this mapping applies to.
         * @param aggregatorSupplier An Aggregation-specific specialization of AggregatorSupplier which will construct the mapped aggregator
         *                           from the aggregation standard set of parameters
         */
        public void register(String aggregationName, List<ValuesSourceType> valuesSourceTypes, AggregatorSupplier aggregatorSupplier) {
            register(aggregationName, (candidate) -> {
                for (ValuesSourceType valuesSourceType : valuesSourceTypes) {
                    if (valuesSourceType.equals(candidate)) {
                        return true;
                    }
                }
                return false;
            }, aggregatorSupplier);
        }

        /**
         * Register an aggregator that applies to any values source type.  This is a convenience method for aggregations that do not care at
         * all about the types of their inputs.  Aggregations using this version of registration should not make any other registrations, as
         * the aggregator registered using this function will be applied in all cases.
         *
         * @param aggregationName The name of the family of aggregations, typically found via
         *                        {@link ValuesSourceAggregationBuilder#getType()}
         * @param aggregatorSupplier An Aggregation-specific specialization of AggregatorSupplier which will construct the mapped aggregator
         *                           from the aggregation standard set of parameters.
         */
        public void registerAny(String aggregationName, AggregatorSupplier aggregatorSupplier) {
            register(aggregationName, (ignored) -> true, aggregatorSupplier);
        }


        public ValuesSourceRegistry build() {
            return new ValuesSourceRegistry(aggregatorRegistry);
        }
    }

    /** Maps Aggregation names to (ValuesSourceType, Supplier) pairs, keyed by ValuesSourceType */
    private Map<String, List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>>> aggregatorRegistry;
    public ValuesSourceRegistry(Map<String, List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>>> aggregatorRegistry) {
        /*
         Make an immutatble copy of our input map.  Since this is write once, read many, we'll spend a bit of extra time to shape this
         into a Map.of(), which is more read optimized than just using a hash map.
         */
        Map.Entry[] copiedEntries = new Map.Entry[aggregatorRegistry.size()];
        int i = 0;
        for (Map.Entry<String, List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>>> entry : aggregatorRegistry.entrySet()) {
            String aggName = entry.getKey();
            List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>> values = entry.getValue();
            Map.Entry newEntry = Map.entry(aggName, List.of(values.toArray()));
            copiedEntries[i++] = newEntry;
        }
        this.aggregatorRegistry = Map.ofEntries(copiedEntries);
    }

    private AggregatorSupplier findMatchingSuppier(ValuesSourceType valuesSourceType,
                                                   List<Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier>> supportedTypes) {
        for (Map.Entry<Predicate<ValuesSourceType>, AggregatorSupplier> candidate : supportedTypes) {
            if (candidate.getKey().test(valuesSourceType)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    public AggregatorSupplier getAggregator(ValuesSourceType valuesSourceType, String aggregationName) {
        if (aggregationName != null && aggregatorRegistry.containsKey(aggregationName)) {
            AggregatorSupplier supplier = findMatchingSuppier(valuesSourceType, aggregatorRegistry.get(aggregationName));
            if (supplier == null) {
                throw new AggregationExecutionException("ValuesSource type " + valuesSourceType.toString() +
                    " is not supported for aggregation" + aggregationName);
            }
            return supplier;
        }
        throw  new AggregationExecutionException("Unregistered Aggregation [" + aggregationName + "]");
    }

    public ValuesSourceType getValuesSourceType(MappedFieldType fieldType, String aggregationName,
                                                // TODO: the following arguments are only needed for the legacy case
                                                IndexFieldData indexFieldData,
                                                ValueType valueType,
                                                ValuesSourceType defaultValuesSourceType) {
        if (aggregationName != null && aggregatorRegistry.containsKey(aggregationName)) {
            // This will throw if the field doesn't support values sources, although really we probably threw much earlier in that case
            ValuesSourceType valuesSourceType = fieldType.getValuesSourceType();
            if (aggregatorRegistry.get(aggregationName) != null
                && findMatchingSuppier(valuesSourceType, aggregatorRegistry.get(aggregationName)) != null) {
                return valuesSourceType;
            }
            String fieldDescription = fieldType.typeName() + "(" + fieldType.toString() + ")";
            throw new IllegalArgumentException("Field [" + fieldType.name() + "] of type [" + fieldDescription +
                "] is not supported for aggregation [" + aggregationName + "]");
        } else {
            // TODO: Legacy resolve logic; remove this after converting all aggregations to the new system
            if (indexFieldData instanceof IndexNumericFieldData) {
                return CoreValuesSourceType.NUMERIC;
            } else if (indexFieldData instanceof IndexGeoPointFieldData) {
                return CoreValuesSourceType.GEOPOINT;
            } else if (fieldType instanceof RangeFieldMapper.RangeFieldType) {
                return CoreValuesSourceType.RANGE;
            } else {
                if (valueType == null) {
                    return defaultValuesSourceType;
                } else {
                    return valueType.getValuesSourceType();
                }
            }
        }
    }
}
