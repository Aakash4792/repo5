/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.search;

import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.core.ml.action.InferModelAction;
import org.elasticsearch.xpack.core.ml.inference.results.TextExpansionResults;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextExpansionConfigUpdate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class SparseVectorQueryBuilder extends AbstractQueryBuilder<SparseVectorQueryBuilder> {
    public static final String NAME = "sparse_vector";
    public static final String ALLOWED_FIELD_TYPE = "sparse_vector";
    public static final ParseField FIELD_FIELD = new ParseField("field");
    public static final ParseField QUERY_VECTOR_FIELD = new ParseField("query_vector");
    public static final ParseField INFERENCE_ID_FIELD = new ParseField("inference_id");
    public static final ParseField QUERY_FIELD = new ParseField("query");
    public static final ParseField PRUNE_FIELD = new ParseField("prune");
    public static final ParseField PRUNING_CONFIG_FIELD = new ParseField("pruning_config");

    private static final boolean DEFAULT_PRUNE = false;

    private final String fieldName;
    private final List<WeightedToken> queryVectors;
    private final String inferenceId;
    private final String query;
    private final boolean shouldPruneTokens;

    private final SetOnce<TextExpansionResults> weightedTokensSupplier;

    @Nullable
    private final TokenPruningConfig tokenPruningConfig;

    public SparseVectorQueryBuilder(String fieldName, String inferenceId, String query) {
        this(fieldName, null, inferenceId, query, DEFAULT_PRUNE, null);
    }

    public SparseVectorQueryBuilder(
        String fieldName,
        @Nullable List<WeightedToken> queryVectors,
        @Nullable String inferenceId,
        @Nullable String query,
        @Nullable Boolean shouldPruneTokens,
        @Nullable TokenPruningConfig tokenPruningConfig
    ) {
        this.fieldName = Objects.requireNonNull(fieldName, "[" + NAME + "] requires a [" + FIELD_FIELD.getPreferredName() + "]");
        this.shouldPruneTokens = (shouldPruneTokens != null ? shouldPruneTokens : DEFAULT_PRUNE);
        this.queryVectors = queryVectors;
        this.inferenceId = inferenceId;
        this.query = query;
        this.tokenPruningConfig = (tokenPruningConfig != null
            ? tokenPruningConfig
            : (this.shouldPruneTokens ? new TokenPruningConfig() : null));
        this.weightedTokensSupplier = null;

        // Preserve BWC error messaging
        if (queryVectors != null && inferenceId != null) {
            throw new IllegalArgumentException(
                "["
                    + NAME
                    + "] requires one of ["
                    + QUERY_VECTOR_FIELD.getPreferredName()
                    + "] or ["
                    + INFERENCE_ID_FIELD.getPreferredName()
                    + "] for "
                    + ALLOWED_FIELD_TYPE
                    + " fields"
            );
        }

        // Preserve BWC error messaging
        if ((queryVectors == null) == (query == null)) {
            throw new IllegalArgumentException(
                "["
                    + NAME
                    + "] requires one of ["
                    + QUERY_VECTOR_FIELD.getPreferredName()
                    + "] or ["
                    + INFERENCE_ID_FIELD.getPreferredName()
                    + "] for "
                    + ALLOWED_FIELD_TYPE
                    + " fields"
            );
        }
    }

    public SparseVectorQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.fieldName = in.readString();
        this.shouldPruneTokens = in.readBoolean();
        this.queryVectors = in.readOptionalCollectionAsList(WeightedToken::new);
        this.inferenceId = in.readOptionalString();
        this.query = in.readOptionalString();
        this.tokenPruningConfig = in.readOptionalWriteable(TokenPruningConfig::new);
        this.weightedTokensSupplier = null;
    }

    private SparseVectorQueryBuilder(SparseVectorQueryBuilder other, SetOnce<TextExpansionResults> weightedTokensSupplier) {
        this.fieldName = other.fieldName;
        this.shouldPruneTokens = other.shouldPruneTokens;
        this.queryVectors = other.queryVectors;
        this.inferenceId = other.inferenceId;
        this.query = other.query;
        this.tokenPruningConfig = other.tokenPruningConfig;
        this.weightedTokensSupplier = weightedTokensSupplier;
    }

    public String getFieldName() {
        return fieldName;
    }

    public List<WeightedToken> getQueryVectors() {
        return queryVectors;
    }

    public String getInferenceId() {
        return inferenceId;
    }

    public String getQuery() {
        return query;
    }

    public boolean shouldPruneTokens() {
        return shouldPruneTokens;
    }

    public TokenPruningConfig getTokenPruningConfig() {
        return tokenPruningConfig;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        if (weightedTokensSupplier != null) {
            throw new IllegalStateException("weighted tokens supplier must be null, can't serialize suppliers, missing a rewriteAndFetch?");
        }

        out.writeString(fieldName);
        out.writeBoolean(shouldPruneTokens);
        out.writeOptionalCollection(queryVectors);
        out.writeOptionalString(inferenceId);
        out.writeOptionalString(query);
        out.writeOptionalWriteable(tokenPruningConfig);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(FIELD_FIELD.getPreferredName(), fieldName);
        if (queryVectors != null) {
            builder.startObject(QUERY_VECTOR_FIELD.getPreferredName());
            for (var token : queryVectors) {
                token.toXContent(builder, params);
            }
            builder.endObject();
        } else {
            if (inferenceId != null) {
                builder.field(INFERENCE_ID_FIELD.getPreferredName(), inferenceId);
            }
            builder.field(QUERY_FIELD.getPreferredName(), query);
        }
        builder.field(PRUNE_FIELD.getPreferredName(), shouldPruneTokens);
        if (tokenPruningConfig != null) {
            builder.field(PRUNING_CONFIG_FIELD.getPreferredName(), tokenPruningConfig);
        }
        boostAndQueryNameToXContent(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        if (queryVectors == null) {
            return new MatchNoDocsQuery("Empty query vectors");
        }

        final MappedFieldType ft = context.getFieldType(fieldName);
        if (ft == null) {
            return new MatchNoDocsQuery("The \"" + getName() + "\" query is against a field that does not exist");
        }

        final String fieldTypeName = ft.typeName();
        if (fieldTypeName.equals(ALLOWED_FIELD_TYPE) == false) {
            throw new IllegalArgumentException(
                "field [" + fieldName + "] must be type [" + ALLOWED_FIELD_TYPE + "] but is type [" + fieldTypeName + "]"
            );
        }

        return rewrite(context).toQuery(context);
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext context) throws IOException {
        if (queryVectors == null) {
            return QueryBuilders.wrapperQuery(new MatchNoDocsQuery().toString());
        }

        if (context instanceof SearchExecutionContext == false) {
            return this;
        }

        SearchExecutionContext searchContext = (SearchExecutionContext) context;
        MappedFieldType fieldType = searchContext.getFieldType(fieldName);
        if (fieldType == null || fieldType.typeName().equals(ALLOWED_FIELD_TYPE) == false) {
            throw new IllegalArgumentException(
                "field ["
                    + fieldName
                    + "] is of unsupported type ["
                    + (fieldType == null ? "null" : fieldType.typeName())
                    + "] for sparse vector query. Supported types are ["
                    + ALLOWED_FIELD_TYPE
                    + "]"
            );
        }

        if (weightedTokensSupplier != null && weightedTokensSupplier.get() != null) {
            return new SparseVectorQueryBuilder(this, weightedTokensSupplier);
        }

        if (inferenceId == null) {
            return this;
        }

        var listener = new SetOnce<TextExpansionResults>();
        try {
            var request = InferModelAction.Request.forTextInput(
                inferenceId,
                TextExpansionConfigUpdate.EMPTY_UPDATE,
                List.of(query),
                false,
                TimeValue.timeValueSeconds(30)
            );
            SetOnce<InferModelAction.Response> responseSupplier = new SetOnce<>();
            context.registerAsyncAction((client, actionListener) -> {
                client.execute(InferModelAction.INSTANCE, request, actionListener.delegateFailureAndWrap((l, response) -> {
                    responseSupplier.set(response);
                    actionListener.onResponse(null);
                }));
            });

            if (responseSupplier.get() == null) {
                throw new ElasticsearchStatusException("Failed to get inference results", RestStatus.INTERNAL_SERVER_ERROR);
            }

            var response = responseSupplier.get();
            InferenceResults inferenceResults = response.getInferenceResults().get(0);
            if (inferenceResults instanceof TextExpansionResults == false) {
                throw new ElasticsearchStatusException(
                    "Unexpected inference response type [{}] from model [{}]",
                    RestStatus.INTERNAL_SERVER_ERROR,
                    inferenceResults.getClass().getSimpleName(),
                    inferenceId
                );
            }
            listener.set((TextExpansionResults) inferenceResults);

            return new SparseVectorQueryBuilder(this, listener);
        } catch (Exception e) {
            throw new ElasticsearchStatusException(
                "Failed to get inference response from model [{}]",
                RestStatus.INTERNAL_SERVER_ERROR,
                e,
                inferenceId
            );
        }
    }

    @Override
    protected boolean doEquals(SparseVectorQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName)
            && Objects.equals(tokenPruningConfig, other.tokenPruningConfig)
            && Objects.equals(queryVectors, other.queryVectors)
            && Objects.equals(shouldPruneTokens, other.shouldPruneTokens)
            && Objects.equals(inferenceId, other.inferenceId)
            && Objects.equals(query, other.query);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(fieldName, queryVectors, tokenPruningConfig, shouldPruneTokens, inferenceId, query);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.V_8_15_0;
    }

    private static final ConstructingObjectParser<SparseVectorQueryBuilder, Void> PARSER = new ConstructingObjectParser<>(NAME, a -> {
        String fieldName = (String) a[0];
        @SuppressWarnings("unchecked")
        List<WeightedToken> weightedTokens = parseWeightedTokens((Map<String, Object>) a[1]);
        String inferenceId = (String) a[2];
        String text = (String) a[3];
        Boolean shouldPruneTokens = (Boolean) a[4];
        TokenPruningConfig tokenPruningConfig = (TokenPruningConfig) a[5];
        return new SparseVectorQueryBuilder(fieldName, weightedTokens, inferenceId, text, shouldPruneTokens, tokenPruningConfig);
    });

    private static List<WeightedToken> parseWeightedTokens(Map<String, Object> weightedTokenMap) {
        List<WeightedToken> weightedTokens = null;
        if (weightedTokenMap != null) {
            weightedTokens = new ArrayList<>();
            for (Map.Entry<String, Object> entry : weightedTokenMap.entrySet()) {
                String token = entry.getKey();
                Object weight = entry.getValue();
                if (weight instanceof Number number) {
                    WeightedToken weightedToken = new WeightedToken(token, number.floatValue());
                    weightedTokens.add(weightedToken);
                } else {
                    throw new IllegalArgumentException("weight must be a number, was [" + weight + "]");
                }
            }
        }
        return weightedTokens;
    }

    static {
        PARSER.declareString(constructorArg(), FIELD_FIELD);
        PARSER.declareObject(optionalConstructorArg(), (p, c) -> p.map(), QUERY_VECTOR_FIELD);
        PARSER.declareString(optionalConstructorArg(), INFERENCE_ID_FIELD);
        PARSER.declareString(optionalConstructorArg(), QUERY_FIELD);
        PARSER.declareBoolean(optionalConstructorArg(), PRUNE_FIELD);
        PARSER.declareObject(optionalConstructorArg(), (p, c) -> TokenPruningConfig.fromXContent(p), PRUNING_CONFIG_FIELD);
        declareStandardFields(PARSER);
    }

    public static SparseVectorQueryBuilder fromXContent(XContentParser parser) {
        try {
            return PARSER.apply(parser, null);
        } catch (IllegalArgumentException e) {
            throw new ParsingException(parser.getTokenLocation(), e.getMessage(), e);
        }
    }
}
