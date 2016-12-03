package org.elassandra;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.cassandra.db.ConsistencyLevel;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESSingleNodeTestCase;

public class CqlTypesTests extends ESSingleNodeTestCase {
    
    public void testTest() throws Exception {
        createIndex("cmdb");
        ensureGreen("cmdb");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE cmdb.server ( name text, ip inet, netmask int, prod boolean, primary key (name))");
        assertAcked(client().admin().indices().preparePutMapping("cmdb")
                .setType("server")
                .setSource("{ \"server\" : { \"discover\" : \".*\", \"properties\": { \"name\":{ \"type\":\"string\", \"index\":\"not_analyzed\" }}}}")
                .get());
        
        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('localhost','127.0.0.1',8,true)");
        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('my-server','123.45.67.78',24,true)");
        
        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("my-server").get().isExists(), equalTo(true));
        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("localhost").get().isExists(), equalTo(true));
        
        assertThat(client().prepareIndex("cmdb", "server", "bigserver234")
            .setSource("{\"ip\": \"22.22.22.22\", \"netmask\":32, \"prod\" : true, \"description\": \"my big server\" }")
            .get().isCreated(), equalTo(true));
        
        assertThat(client().prepareSearch().setIndices("cmdb").setTypes("server").setQuery(QueryBuilders.queryStringQuery("*:*")).get().getHits().getTotalHits(), equalTo(3L));
    }

    public void testAllTypesTest() throws Exception {
        createIndex("ks1");
        ensureGreen("ks1");
        
        process(ConsistencyLevel.ONE,
                "CREATE TABLE ks1.natives (c1 text primary key, c2 text, c3 timestamp, c4 int, c5 bigint, c6 double, c7 float, c8 boolean, c9 blob)");
        assertAcked(client().admin().indices()
                .preparePutMapping("ks1")
                .setType("natives")
                .setSource("{ \"natives\" : { \"discover\" : \".*\", \"properties\": { \"c2\":{ \"type\":\"string\", \"index\":\"not_analyzed\" }}}}")
                .get());
        
        assertThat(client().prepareIndex("ks1", "natives", "1")
                .setSource("{\"c2\": \"toto\", \"c3\" : \"2016-10-10\", \"c4\": 1, \"c5\":44, \"c6\":1.0, \"c7\":2.22, \"c8\": true, \"c9\":\"U29tZSBiaW5hcnkgYmxvYg==\" }")
                .get().isCreated(), equalTo(true));
        Map<String,Object> fields = client().prepareSearch("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c2:toto")).get().getHits().getHits()[0].getSource();
        assertThat(fields.get("c2"),equalTo("toto"));
        assertThat(fields.get("c3").toString(),equalTo("2016-10-10T00:00:00.000Z"));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(44));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.22));
        assertThat(fields.get("c8"),equalTo(true));
        assertThat(fields.get("c9"),equalTo("U29tZSBiaW5hcnkgYmxvYg=="));
        
        process(ConsistencyLevel.ONE,"insert into ks1.natives (c1,c2,c3,c4,c5,c6,c7,c8,c9) VALUES ('tutu', 'titi', '2016-11-11', 1, 45, 1.0, 2.23, false,textAsBlob('bdb14fbe076f6b94444c660e36a400151f26fc6f'))");
        assertThat(client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("*:*")).get().getHits().getTotalHits(), equalTo(2L));
        
        fields = client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c5:45")).get().getHits().getHits()[0].getSource();
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone(System.getProperty("tests.timezone")));
        
        assertThat(fields.get("c2"), equalTo("titi"));
//        assertThat(fields.get("c3"), equalTo(new SimpleDateFormat("yyyy-MM-dd").parse("2016-11-11").toLocaleString()));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(45));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.23));
        assertThat(fields.get("c8"),equalTo(false));
    }

}
