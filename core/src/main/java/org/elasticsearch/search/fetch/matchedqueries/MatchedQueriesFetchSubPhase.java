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
package org.elasticsearch.search.fetch.matchedqueries;

import com.google.common.collect.ImmutableMap;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.search.SearchParseElement;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.SearchContext.Lifetime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class MatchedQueriesFetchSubPhase implements FetchSubPhase {

    @Override
    public Map<String, ? extends SearchParseElement> parseElements() {
        return ImmutableMap.of();
    }

    @Override
    public boolean hitsExecutionNeeded(SearchContext context) {
        return true; // we short-circuit in hitsExecute
    }

    @Override
    public void hitsExecute(SearchContext context, InternalSearchHit[] hits) {
        if (hits.length == 0) {
            return;
        }
        hits = hits.clone(); // don't modify the incoming hits
        Arrays.sort(hits, new Comparator<InternalSearchHit>() {
            @Override
            public int compare(InternalSearchHit a, InternalSearchHit b) {
                return Integer.compare(a.docId(), b.docId());
            }
        });
        @SuppressWarnings("unchecked")
        List<String>[] matchedQueries = new List[hits.length];
        for (int i = 0; i < matchedQueries.length; ++i) {
            matchedQueries[i] = new ArrayList<>();
        }

        Map<String, Query> namedQueries = new HashMap<>(context.parsedQuery().namedFilters());
        if (context.parsedPostFilter() != null) {
            namedQueries.putAll(context.parsedPostFilter().namedFilters());
        }

        try {
            for (Map.Entry<String, Query> entry : namedQueries.entrySet()) {
                String name = entry.getKey();
                Query query = entry.getValue();
                int readerIndex = -1;
                int docBase = -1;
                Weight weight = context.searcher().createNormalizedWeight(query, false);
                Bits matchingDocs = null;
                for (int i = 0; i < hits.length; ++i) {
                    InternalSearchHit hit = hits[i];
                    int hitReaderIndex = ReaderUtil.subIndex(hit.docId(), context.searcher().getIndexReader().leaves());
                    if (readerIndex != hitReaderIndex) {
                        readerIndex = hitReaderIndex;
                        LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(readerIndex);
                        docBase = ctx.docBase;
                        // scorers can be costly to create, so reuse them across docs of the same segment
                        Scorer scorer = weight.scorer(ctx);
                        matchingDocs = Lucene.asSequentialAccessBits(ctx.reader().maxDoc(), scorer);
                    }
                    if (matchingDocs.get(hit.docId() - docBase)) {
                        matchedQueries[i].add(name);
                    }
                }
            }
            for (int i = 0; i < hits.length; ++i) {
                hits[i].matchedQueries(matchedQueries[i].toArray(new String[0]));
            }
        } catch (IOException e) {
            throw ExceptionsHelper.convertToElastic(e);
        } finally {
            SearchContext.current().clearReleasables(Lifetime.COLLECTION);
        }
    }

    @Override
    public boolean hitExecutionNeeded(SearchContext context) {
        return false;
    }

    @Override
    public void hitExecute(SearchContext context, HitContext hitContext) {
        // we do everything in hitsExecute
    }
}
