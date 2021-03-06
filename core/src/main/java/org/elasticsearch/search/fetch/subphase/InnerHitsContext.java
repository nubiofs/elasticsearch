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

package org.elasticsearch.search.fetch.subphase;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocValuesTermsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ParentChildrenBlockJoinQuery;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.SubSearchContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class InnerHitsContext {

    private final Map<String, BaseInnerHits> innerHits;

    public InnerHitsContext() {
        this.innerHits = new HashMap<>();
    }

    public InnerHitsContext(Map<String, BaseInnerHits> innerHits) {
        this.innerHits = Objects.requireNonNull(innerHits);
    }

    public Map<String, BaseInnerHits> getInnerHits() {
        return innerHits;
    }

    public void addInnerHitDefinition(BaseInnerHits innerHit) {
        if (innerHits.containsKey(innerHit.getName())) {
            throw new IllegalArgumentException("inner_hit definition with the name [" + innerHit.getName() +
                    "] already exists. Use a different inner_hit name or define one explicitly");
        }

        innerHits.put(innerHit.getName(), innerHit);
    }

    public abstract static class BaseInnerHits extends SubSearchContext {

        private final String name;
        private InnerHitsContext childInnerHits;

        protected BaseInnerHits(String name, SearchContext context) {
            super(context);
            this.name = name;
        }

        public abstract TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext) throws IOException;

        public String getName() {
            return name;
        }

        @Override
        public InnerHitsContext innerHits() {
            return childInnerHits;
        }

        public void setChildInnerHits(Map<String, InnerHitsContext.BaseInnerHits> childInnerHits) {
            this.childInnerHits = new InnerHitsContext(childInnerHits);
        }
    }

    public static final class NestedInnerHits extends BaseInnerHits {

        private final ObjectMapper parentObjectMapper;
        private final ObjectMapper childObjectMapper;

        public NestedInnerHits(String name, SearchContext context, ObjectMapper parentObjectMapper, ObjectMapper childObjectMapper) {
            super(name != null ? name : childObjectMapper.fullPath(), context);
            this.parentObjectMapper = parentObjectMapper;
            this.childObjectMapper = childObjectMapper;
        }

        @Override
        public TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext) throws IOException {
            Query rawParentFilter;
            if (parentObjectMapper == null) {
                rawParentFilter = Queries.newNonNestedFilter();
            } else {
                rawParentFilter = parentObjectMapper.nestedTypeFilter();
            }
            BitSetProducer parentFilter = context.bitsetFilterCache().getBitSetProducer(rawParentFilter);
            Query childFilter = childObjectMapper.nestedTypeFilter();
            int parentDocId = hitContext.readerContext().docBase + hitContext.docId();
            Query q = Queries.filtered(query(), new ParentChildrenBlockJoinQuery(parentFilter, childFilter, parentDocId));

            if (size() == 0) {
                return new TopDocs(context.searcher().count(q), Lucene.EMPTY_SCORE_DOCS, 0);
            } else {
                int topN = Math.min(from() + size(), context.searcher().getIndexReader().maxDoc());
                TopDocsCollector topDocsCollector;
                if (sort() != null) {
                    try {
                        topDocsCollector = TopFieldCollector.create(sort().sort, topN, true, trackScores(), trackScores());
                    } catch (IOException e) {
                        throw ExceptionsHelper.convertToElastic(e);
                    }
                } else {
                    topDocsCollector = TopScoreDocCollector.create(topN);
                }
                try {
                    context.searcher().search(q, topDocsCollector);
                } finally {
                    clearReleasables(Lifetime.COLLECTION);
                }
                return topDocsCollector.topDocs(from(), size());
            }
        }

    }

    public static final class ParentChildInnerHits extends BaseInnerHits {

        private final MapperService mapperService;
        private final DocumentMapper documentMapper;

        public ParentChildInnerHits(String name, SearchContext context, MapperService mapperService, DocumentMapper documentMapper) {
            super(name != null ? name : documentMapper.type(), context);
            this.mapperService = mapperService;
            this.documentMapper = documentMapper;
        }

        @Override
        public TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext) throws IOException {
            final Query hitQuery;
            if (isParentHit(hitContext.hit())) {
                String field = ParentFieldMapper.joinField(hitContext.hit().getType());
                hitQuery = new DocValuesTermsQuery(field, hitContext.hit().getId());
            } else if (isChildHit(hitContext.hit())) {
                DocumentMapper hitDocumentMapper = mapperService.documentMapper(hitContext.hit().getType());
                final String parentType = hitDocumentMapper.parentFieldMapper().type();
                SearchHitField parentField = hitContext.hit().field(ParentFieldMapper.NAME);
                if (parentField == null) {
                    throw new IllegalStateException("All children must have a _parent");
                }
                hitQuery = new TermQuery(new Term(UidFieldMapper.NAME, Uid.createUid(parentType, parentField.getValue())));
            } else {
                return Lucene.EMPTY_TOP_DOCS;
            }

            BooleanQuery q = new BooleanQuery.Builder()
                .add(query(), Occur.MUST)
                // Only include docs that have the current hit as parent
                .add(hitQuery, Occur.FILTER)
                // Only include docs that have this inner hits type
                .add(documentMapper.typeFilter(), Occur.FILTER)
                .build();
            if (size() == 0) {
                final int count = context.searcher().count(q);
                return new TopDocs(count, Lucene.EMPTY_SCORE_DOCS, 0);
            } else {
                int topN = Math.min(from() + size(), context.searcher().getIndexReader().maxDoc());
                TopDocsCollector topDocsCollector;
                if (sort() != null) {
                    topDocsCollector = TopFieldCollector.create(sort().sort, topN, true, trackScores(), trackScores());
                } else {
                    topDocsCollector = TopScoreDocCollector.create(topN);
                }
                try {
                    context.searcher().search(q, topDocsCollector);
                } finally {
                    clearReleasables(Lifetime.COLLECTION);
                }
                return topDocsCollector.topDocs(from(), size());
            }
        }

        private boolean isParentHit(SearchHit hit) {
            return hit.getType().equals(documentMapper.parentFieldMapper().type());
        }

        private boolean isChildHit(SearchHit hit) {
            DocumentMapper hitDocumentMapper = mapperService.documentMapper(hit.getType());
            return documentMapper.type().equals(hitDocumentMapper.parentFieldMapper().type());
        }
    }
}
