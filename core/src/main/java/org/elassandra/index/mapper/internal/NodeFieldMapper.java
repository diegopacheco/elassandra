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

package org.elassandra.index.mapper.internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.core.StringFieldMapper;

/**
 *  Mapper for the _node field, the cassandra host id.
 **/
public class NodeFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_node";
    public static final String CONTENT_TYPE = "_node";

    public static class Defaults extends StringFieldMapper.Defaults {
        public static final String NAME = NodeFieldMapper.NAME;
        public static final MappedFieldType NODE_FIELD_TYPE = new NodeFieldType();

        static {
            NODE_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            NODE_FIELD_TYPE.setStored(false);
            NODE_FIELD_TYPE.setOmitNorms(true);
            NODE_FIELD_TYPE.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            NODE_FIELD_TYPE.setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
            NODE_FIELD_TYPE.setNames(new MappedFieldType.Names(NAME));
            NODE_FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends MetadataFieldMapper.Builder<Builder, NodeFieldMapper> {

        public Builder() {
            super(Defaults.NAME, Defaults.NODE_FIELD_TYPE, Defaults.FIELD_TYPE);
        }

        @Override
        public NodeFieldMapper build(BuilderContext context) {
            return new NodeFieldMapper(context.indexSettings());
        }
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder<?, ?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder();
            /*
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                
            }
            */
            return builder;
        }

        @Override
        public MetadataFieldMapper getDefault(Settings indexSettings, MappedFieldType fieldType, String typeName) {
            return new NodeFieldMapper(indexSettings);
        }
    }

    static final class NodeFieldType extends MappedFieldType {

        public NodeFieldType() {
            setFieldDataType(new FieldDataType("string"));
        }

        protected NodeFieldType(NodeFieldType  ref) {
            super(ref);
        }

        @Override
        public NodeFieldType clone() {
            return new NodeFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

    }

    private NodeFieldMapper(Settings indexSettings) {
        super(NAME, Defaults.NODE_FIELD_TYPE, Defaults.NODE_FIELD_TYPE, indexSettings);
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
    }

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        return null;
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
        super.parse(context);
    }

    
    @Override
    public void createField(ParseContext context, Object object) throws IOException {
        String node = (String)object;
        if (node != null) {
            context.doc().add(new Field(fieldType().names().indexName(), node, fieldType()));
            context.doc().add(new SortedDocValuesField(fieldType().names().indexName(), new BytesRef(node)));
        }
    }
    
    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        
    }

    
    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        // nothing to do
    }

}
