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
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.search.aggregations.bucket.filter.Filter;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Order;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.AbstractNumericTestCase;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentile;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentileRanks;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentilesMethod;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.util.CollectionUtils.iterableAsArrayList;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.global;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.percentileRanks;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

/**
 *
 */
public class HDRPercentileRanksTests extends AbstractNumericTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(GroovyPlugin.class);
    }

    private static double[] randomPercents(long minValue, long maxValue) {

        final int length = randomIntBetween(1, 20);
        final double[] percents = new double[length];
        for (int i = 0; i < percents.length; ++i) {
            switch (randomInt(20)) {
            case 0:
                percents[i] = minValue;
                break;
            case 1:
                percents[i] = maxValue;
                break;
            default:
                percents[i] = (randomDouble() * (maxValue - minValue)) + minValue;
                break;
            }
        }
        Arrays.sort(percents);
        Loggers.getLogger(HDRPercentileRanksTests.class).info("Using percentiles={}", Arrays.toString(percents));
        return percents;
    }

    private static int randomSignificantDigits() {
        return randomIntBetween(0, 5);
    }

    private void assertConsistent(double[] pcts, PercentileRanks percentiles, long minValue, long maxValue, int numberSigDigits) {
        final List<Percentile> percentileList = iterableAsArrayList(percentiles);
        assertEquals(pcts.length, percentileList.size());
        for (int i = 0; i < pcts.length; ++i) {
            final Percentile percentile = percentileList.get(i);
            assertThat(percentile.getValue(), equalTo(pcts[i]));
            assertThat(percentile.getPercent(), greaterThanOrEqualTo(0.0));
            assertThat(percentile.getPercent(), lessThanOrEqualTo(100.0));

            if (percentile.getPercent() == 0) {
                double allowedError = minValue / Math.pow(10, numberSigDigits);
                assertThat(percentile.getValue(), lessThanOrEqualTo(minValue + allowedError));
            }
            if (percentile.getPercent() == 100) {
                double allowedError = maxValue / Math.pow(10, numberSigDigits);
                assertThat(percentile.getValue(), greaterThanOrEqualTo(maxValue - allowedError));
            }
        }

        for (int i = 1; i < percentileList.size(); ++i) {
            assertThat(percentileList.get(i).getValue(), greaterThanOrEqualTo(percentileList.get(i - 1).getValue()));
        }
    }

    @Override
    @Test
    public void testEmptyAggregation() throws Exception {

        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo")
                                .field("value")
                                .interval(1l)
                                .minDocCount(0)
                                .subAggregation(
                                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR)
                                                .numberOfSignificantValueDigits(sigDigits).percentiles(10, 15))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        PercentileRanks reversePercentiles = bucket.getAggregations().get("percentile_ranks");
        assertThat(reversePercentiles, notNullValue());
        assertThat(reversePercentiles.getName(), equalTo("percentile_ranks"));
        assertThat(reversePercentiles.percent(10), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(15), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testUnmapped() throws Exception {
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").percentiles(0, 10, 15, 100)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(0l));

        PercentileRanks reversePercentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertThat(reversePercentiles, notNullValue());
        assertThat(reversePercentiles.getName(), equalTo("percentile_ranks"));
        assertThat(reversePercentiles.percent(0), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(10), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(15), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(100), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testSingleValuedField() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_getProperty() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        global("global").subAggregation(
                                percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                        .field("value").percentiles(pcts))).execute().actionGet();

        assertHitCount(searchResponse, 10);

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(10l));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        PercentileRanks percentiles = global.getAggregations().get("percentile_ranks");
        assertThat(percentiles, notNullValue());
        assertThat(percentiles.getName(), equalTo("percentile_ranks"));
        assertThat((PercentileRanks) global.getProperty("percentile_ranks"), sameInstance(percentiles));

    }

    @Test
    public void testSingleValuedFieldOutsideRange() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = new double[] { minValue - 1, maxValue + 1 };
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_PartiallyUnmapped() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client()
                .prepareSearch("idx", "idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").script(new Script("_value - 1")).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript_WithParams() throws Exception {
        int sigDigits = randomSignificantDigits();
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("value").script(new Script("_value - dec", ScriptType.INLINE, null, params)).percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValues, maxValues);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("values").percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues, maxValues, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("values").script(new Script("_value - 1")).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Test
    public void testMultiValuedField_WithValueScript_Reverse() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(20 - maxValues, 20 - minValues);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("values").script(new Script("20 - _value")).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, 20 - maxValues, 20 - minValues, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript_WithParams() throws Exception {
        int sigDigits = randomSignificantDigits();
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .field("values").script(new Script("_value - dec", ScriptType.INLINE, null, params)).percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Override
    @Test
    public void testScript_SingleValued() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .script(new Script("doc['value'].value")).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testScript_SingleValued_WithParams() throws Exception {
        int sigDigits = randomSignificantDigits();
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .script(new Script("doc['value'].value - dec", ScriptType.INLINE, null, params)).percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    public void testScriptMultiValued() throws Exception {
        int sigDigits = randomSignificantDigits();
        final double[] pcts = randomPercents(minValues, maxValues);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                .script(new Script("doc['values'].values")).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues, maxValues, sigDigits);
    }

    @Override
    public void testScriptMultiValuedWithParams() throws Exception {
        int sigDigits = randomSignificantDigits();
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentileRanks("percentile_ranks")
                                .method(PercentilesMethod.HDR)
                                .numberOfSignificantValueDigits(sigDigits)
                                .script(new Script(
                                        "List values = doc['values'].values; double[] res = new double[values.size()]; for (int i = 0; i < res.length; i++) { res[i] = values.get(i) - dec; }; return res;",
                                        ScriptType.INLINE, null, params)).percentiles(pcts)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Test
    public void testOrderBySubAggregation() {
        int sigDigits = randomSignificantDigits();
        boolean asc = randomBoolean();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field("value").interval(2l)
                                .subAggregation(
                                        percentileRanks("percentile_ranks").method(PercentilesMethod.HDR)
                                                .numberOfSignificantValueDigits(sigDigits).percentiles(99))
                                .order(Order.aggregation("percentile_ranks", "99", asc))).execute().actionGet();

        assertHitCount(searchResponse, 10);

        Histogram histo = searchResponse.getAggregations().get("histo");
        double previous = asc ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (Histogram.Bucket bucket : histo.getBuckets()) {
            PercentileRanks percentiles = bucket.getAggregations().get("percentile_ranks");
            double p99 = percentiles.percent(99);
            if (asc) {
                assertThat(p99, greaterThanOrEqualTo(previous));
            } else {
                assertThat(p99, lessThanOrEqualTo(previous));
            }
            previous = p99;
        }
    }

    @Override
    public void testOrderByEmptyAggregation() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(terms("terms").field("value").order(Terms.Order.compound(Terms.Order.aggregation("filter>ranks.99", true)))
                                .subAggregation(filter("filter").filter(termQuery("value", 100))
                                .subAggregation(percentileRanks("ranks").method(PercentilesMethod.HDR).percentiles(99).field("value"))))
                .get();

        assertHitCount(searchResponse, 10);

        Terms terms = searchResponse.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        List<Terms.Bucket> buckets = terms.getBuckets();
        assertThat(buckets, notNullValue());
        assertThat(buckets.size(), equalTo(10));

        for (int i = 0; i < 10; i++) {
            Terms.Bucket bucket = buckets.get(i);
            assertThat(bucket, notNullValue());
            assertThat(bucket.getKeyAsNumber(), equalTo((Number) Long.valueOf(i + 1)));
            assertThat(bucket.getDocCount(), equalTo(1L));
            Filter filter = bucket.getAggregations().get("filter");
            assertThat(filter, notNullValue());
            assertThat(filter.getDocCount(), equalTo(0L));
            PercentileRanks ranks = filter.getAggregations().get("ranks");
            assertThat(ranks, notNullValue());
            assertThat(ranks.percent(99), equalTo(Double.NaN));

        }
    }

}
