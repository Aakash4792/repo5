/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.features.FeatureSpecification;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.flattened.FlattenedFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;

import java.util.Set;

/**
 * Spec for mapper-related features.
 */
public class MapperFeatures implements FeatureSpecification {
    @Override
    public Set<NodeFeature> getFeatures() {
        return Set.of(
            IgnoredSourceFieldMapper.TRACK_IGNORED_SOURCE,
            PassThroughObjectMapper.PASS_THROUGH_PRIORITY,
            RangeFieldMapper.NULL_VALUES_OFF_BY_ONE_FIX,
            SourceFieldMapper.SYNTHETIC_SOURCE_FALLBACK,
            DenseVectorFieldMapper.INT4_QUANTIZATION,
            DenseVectorFieldMapper.BIT_VECTORS,
            DocumentMapper.INDEX_SORTING_ON_NESTED,
            KeywordFieldMapper.KEYWORD_DIMENSION_IGNORE_ABOVE,
            IndexModeFieldMapper.QUERYING_INDEX_MODE,
            NodeMappingStats.SEGMENT_LEVEL_FIELDS_STATS,
            BooleanFieldMapper.BOOLEAN_DIMENSION,
            ObjectMapper.SUBOBJECTS_AUTO,
            ObjectMapper.SUBOBJECTS_AUTO_FIXES,
            KeywordFieldMapper.KEYWORD_NORMALIZER_SYNTHETIC_SOURCE,
            SourceFieldMapper.SYNTHETIC_SOURCE_STORED_FIELDS_ADVANCE_FIX,
            Mapper.SYNTHETIC_SOURCE_KEEP_FEATURE,
            SourceFieldMapper.SYNTHETIC_SOURCE_WITH_COPY_TO_AND_DOC_VALUES_FALSE_SUPPORT,
            SourceFieldMapper.SYNTHETIC_SOURCE_COPY_TO_FIX,
            FlattenedFieldMapper.IGNORE_ABOVE_SUPPORT,
            IndexSettings.IGNORE_ABOVE_INDEX_LEVEL_SETTING,
            SourceFieldMapper.SYNTHETIC_SOURCE_COPY_TO_INSIDE_OBJECTS_FIX,
            TimeSeriesRoutingHashFieldMapper.TS_ROUTING_HASH_FIELD_PARSES_BYTES_REF,
            FlattenedFieldMapper.IGNORE_ABOVE_WITH_ARRAYS_SUPPORT,
            DenseVectorFieldMapper.BBQ_FORMAT
        );
    }

    public static final NodeFeature CONSTANT_KEYWORD_SYNTHETIC_SOURCE_WRITE_FIX = new NodeFeature(
        "mapper.constant_keyword.synthetic_source_write_fix"
    );

    public static final NodeFeature COUNTED_KEYWORD_SYNTHETIC_SOURCE_NATIVE_SUPPORT = new NodeFeature(
        "mapper.counted_keyword.synthetic_source_native_support"
    );

    public static final NodeFeature META_FETCH_FIELDS_ERROR_CODE_CHANGED = new NodeFeature("meta_fetch_fields_error_code_changed");
    public static final NodeFeature SPARSE_VECTOR_STORE_SUPPORT = new NodeFeature("mapper.sparse_vector.store_support");
    public static final NodeFeature SORT_FIELDS_CHECK_FOR_NESTED_OBJECT_FIX = new NodeFeature("mapper.nested.sorting_fields_check_fix");
    public static final NodeFeature DYNAMIC_HANDLING_IN_COPY_TO = new NodeFeature("mapper.copy_to.dynamic_handling");

    @Override
    public Set<NodeFeature> getTestFeatures() {
        return Set.of(
            RangeFieldMapper.DATE_RANGE_INDEXING_FIX,
            IgnoredSourceFieldMapper.DONT_EXPAND_DOTS_IN_IGNORED_SOURCE,
            SourceFieldMapper.REMOVE_SYNTHETIC_SOURCE_ONLY_VALIDATION,
            SourceFieldMapper.SOURCE_MODE_FROM_INDEX_SETTING,
            IgnoredSourceFieldMapper.ALWAYS_STORE_OBJECT_ARRAYS_IN_NESTED_OBJECTS,
            MapperService.LOGSDB_DEFAULT_IGNORE_DYNAMIC_BEYOND_LIMIT,
            DocumentParser.FIX_PARSING_SUBOBJECTS_FALSE_DYNAMIC_FALSE,
            CONSTANT_KEYWORD_SYNTHETIC_SOURCE_WRITE_FIX,
            META_FETCH_FIELDS_ERROR_CODE_CHANGED,
            SPARSE_VECTOR_STORE_SUPPORT,
            COUNTED_KEYWORD_SYNTHETIC_SOURCE_NATIVE_SUPPORT,
            SORT_FIELDS_CHECK_FOR_NESTED_OBJECT_FIX,
            DYNAMIC_HANDLING_IN_COPY_TO,
            SourceFieldMapper.SYNTHETIC_RECOVERY_SOURCE,
            ObjectMapper.SUBOBJECTS_FALSE_MAPPING_UPDATE_FIX
        );
    }
}
