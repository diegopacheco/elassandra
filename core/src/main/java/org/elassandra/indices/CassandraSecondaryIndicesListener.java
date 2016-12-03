/*
 * Copyright (c) 2015 Vincent Royer (vroyer@vroyer.org).
 * Contains some code from Elasticsearch (http://www.elastic.co)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elassandra.indices;

/**
 * Post applied cluster state listener.
 */
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.cassandra.utils.Pair;
import org.elassandra.cluster.InternalCassandraClusterService;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.indices.IndicesLifecycle;

public class CassandraSecondaryIndicesListener implements ClusterStateListener {
    ESLogger logger = Loggers.getLogger(CassandraSecondaryIndicesListener.class);
    
    private final ClusterService clusterService;
    private final CopyOnWriteArraySet<Pair<String,MappingMetaData>> updatedMapping = new CopyOnWriteArraySet<>();
    
    public CassandraSecondaryIndicesListener(ClusterService clusterService) {
        this.clusterService = clusterService;
    }
    
    // called only by the coordinator of a mapping change on pre-applied phase
    public void updateMapping(String index, MappingMetaData mapping) {
        updatedMapping.add(Pair.create(index, mapping));
    }

    // called on post-applied phase (when shards are started on all nodes)
    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        for(Pair<String,MappingMetaData> mapping : updatedMapping) {
            String index = mapping.left;
            IndexMetaData indexMetaData = event.state().metaData().index(index);
            if (indexMetaData != null) {
                try {
                    String clazz = indexMetaData.getSettings().get(IndexMetaData.SETTING_SECONDARY_INDEX_CLASS, event.state().metaData().settings().get(InternalCassandraClusterService.SETTING_CLUSTER_DEFAULT_SECONDARY_INDEX_CLASS, InternalCassandraClusterService.defaultSecondaryIndexClass.getName()));
                    logger.debug("Creating secondary indices for table={}.{} with class={}", indexMetaData.keyspace(), mapping.right.type(),clazz);
                    this.clusterService.createSecondaryIndex(indexMetaData.keyspace(), mapping.right, clazz);
                } catch (IOException e) {
                    logger.error("Failed to create secondary indices for table={}.{}", e, indexMetaData.keyspace(), mapping.right.type());
                }
            } else {
                logger.warn("Index [{}] not found in new state metadata version={}", index, event.state().metaData().version());
            }
        }
        updatedMapping.clear();
    }
}
