/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.rank.RankDoc;
import org.elasticsearch.search.retriever.rankdoc.RankDocsQuery;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class RankDocsQueryBuilder extends AbstractQueryBuilder<RankDocsQueryBuilder> {

    public static final String NAME = "rank_docs_query";
    
    /**
     * Default minimum score threshold for documents to be included in results.
     * Using Float.MIN_VALUE as the default ensures that by default no documents 
     * are filtered out based on score, as virtually all scores will be above this threshold.
     * 
     * This threshold is separate from the special handling of scores that are exactly 0:
     * - The minScore parameter determines which documents are included in results based on their score
     * - Documents with a score of exactly 0 will always be assigned Float.MIN_VALUE internally
     *   to differentiate them from filtered matches, regardless of the minScore value
     *
     * Setting minScore to a higher value (e.g., 0.0f) would filter out documents with scores below that threshold,
     * which can be useful to remove documents that only match filters but have no relevance score contribution.
     */
    public static final float DEFAULT_MIN_SCORE = Float.MIN_VALUE;

    private final RankDoc[] rankDocs;
    private final QueryBuilder[] queryBuilders;
    private final boolean onlyRankDocs;
    private final float minScore;

    public RankDocsQueryBuilder(RankDoc[] rankDocs, QueryBuilder[] queryBuilders, boolean onlyRankDocs) {
        this(rankDocs, queryBuilders, onlyRankDocs, DEFAULT_MIN_SCORE);
    }

    public RankDocsQueryBuilder(RankDoc[] rankDocs, QueryBuilder[] queryBuilders, boolean onlyRankDocs, float minScore) {
        this.rankDocs = rankDocs;
        this.queryBuilders = queryBuilders;
        this.onlyRankDocs = onlyRankDocs;
        this.minScore = minScore;
    }

    public RankDocsQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.rankDocs = in.readArray(c -> c.readNamedWriteable(RankDoc.class), RankDoc[]::new);
        QueryBuilder[] queryBuilders = null;
        boolean onlyRankDocs = false;
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0)) {
            queryBuilders = in.readOptionalArray(c -> c.readNamedWriteable(QueryBuilder.class), QueryBuilder[]::new);
            onlyRankDocs = in.readBoolean();
        }
        this.queryBuilders = queryBuilders;
        this.onlyRankDocs = onlyRankDocs;
        this.minScore = in.getTransportVersion().onOrAfter(TransportVersions.RANK_DOCS_MIN_SCORE) ? in.readFloat() : DEFAULT_MIN_SCORE;
    }

    @Override
    protected void extractInnerHitBuilders(Map<String, InnerHitContextBuilder> innerHits) {
        if (queryBuilders != null) {
            for (QueryBuilder query : queryBuilders) {
                InnerHitContextBuilder.extractInnerHits(query, innerHits);
            }
        }
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        if (queryBuilders != null) {
            QueryBuilder[] newQueryBuilders = new QueryBuilder[queryBuilders.length];
            boolean changed = false;
            for (int i = 0; i < newQueryBuilders.length; i++) {
                newQueryBuilders[i] = queryBuilders[i].rewrite(queryRewriteContext);
                changed |= newQueryBuilders[i] != queryBuilders[i];
            }
            if (changed) {
                RankDocsQueryBuilder clone = new RankDocsQueryBuilder(rankDocs, newQueryBuilders, onlyRankDocs, minScore);
                clone.queryName(queryName());
                return clone;
            }
        }
        return super.doRewrite(queryRewriteContext);
    }

    public RankDoc[] rankDocs() {
        return rankDocs;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeArray(StreamOutput::writeNamedWriteable, rankDocs);
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0)) {
            out.writeOptionalArray(StreamOutput::writeNamedWriteable, queryBuilders);
            out.writeBoolean(onlyRankDocs);
            if (out.getTransportVersion().onOrAfter(TransportVersions.RANK_DOCS_MIN_SCORE)) {
                out.writeFloat(minScore);
            }
        }
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        RankDoc[] shardRankDocs = Arrays.stream(rankDocs)
            .filter(r -> r.shardIndex == context.getShardRequestIndex())
            .toArray(RankDoc[]::new);
        IndexReader reader = context.getIndexReader();
        final Query[] queries;
        final String[] queryNames;
        if (queryBuilders != null) {
            queries = new Query[queryBuilders.length];
            queryNames = new String[queryBuilders.length];
            for (int i = 0; i < queryBuilders.length; i++) {
                queries[i] = queryBuilders[i].toQuery(context);
                queryNames[i] = queryBuilders[i].queryName();
            }
        } else {
            queries = new Query[0];
            queryNames = Strings.EMPTY_ARRAY;
        }
        return new RankDocsQuery(reader, shardRankDocs, queries, queryNames, onlyRankDocs, minScore);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.startArray("rank_docs");
        for (RankDoc doc : rankDocs) {
            builder.startObject();
            doc.toXContent(builder, params);
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
    }

    @Override
    protected boolean doEquals(RankDocsQueryBuilder other) {
        return Arrays.equals(rankDocs, other.rankDocs)
            && Arrays.equals(queryBuilders, other.queryBuilders)
            && onlyRankDocs == other.onlyRankDocs
            && minScore == other.minScore;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(Arrays.hashCode(rankDocs), Arrays.hashCode(queryBuilders), onlyRankDocs, minScore);
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.V_8_16_0;
    }
}
