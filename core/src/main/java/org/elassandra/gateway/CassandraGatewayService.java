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
package org.elassandra.gateway;



import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

public class CassandraGatewayService extends GatewayService {

    public static final ClusterBlock NO_CASSANDRA_RING_BLOCK = new ClusterBlock(12, "no cassandra ring", true, true, RestStatus.SERVICE_UNAVAILABLE, EnumSet.of(ClusterBlockLevel.READ));

    private final ClusterService clusterService;
    private final AllocationService allocationService;
    private final Gateway gateway;
    private final ThreadPool threadPool;
    
    private final AtomicBoolean recovered = new AtomicBoolean();
    
    @Inject
    public CassandraGatewayService(Settings settings, Gateway gateway, AllocationService allocationService, ClusterService clusterService, DiscoveryService discoveryService, ThreadPool threadPool) {
        super(settings, gateway, allocationService, clusterService, discoveryService, threadPool);
        this.clusterService = clusterService;
        this.allocationService = allocationService;
        this.gateway = gateway;
        this.threadPool = threadPool;
    }

    /**
     * release the NO_CASSANDRA_RING_BLOCK and update routingTable since the node'state = NORMAL (i.e a member of the ring) 
     * (may be update when replaying the cassandra logs)
     */
    public void enableMetaDataPersictency() {
        

        clusterService.submitStateUpdateTask("gateway-cassandra-ring-ready", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) {
                logger.debug("releasing the cassandra ring block...");
                
                // remove the block, since we recovered from gateway
                ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks()).removeGlobalBlock(NO_CASSANDRA_RING_BLOCK);
                
                // update the state to reflect 
                ClusterState updatedState = ClusterState.builder(currentState).blocks(blocks).incrementVersion().build();
                return updatedState;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("unexpected failure during [{}]", t, source);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                logger.info("cassandra ring block released");
            }
        });
    }
    
    @Override
    protected void checkStateMeetsSettingsAndMaybeRecover(ClusterState state) {
        /*
        if (state.nodes().localNodeMaster() == false) {
            // not our job to recover
            return;
        }
        */
        if (state.blocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK) == false) {
            // already recovered
            return;
        }
        
        performStateRecovery();
    }
    
    private void performStateRecovery() {
        final Gateway.GatewayStateRecoveredListener recoveryListener = new GatewayRecoveryListener();
        gateway.performStateRecovery(recoveryListener);
        /*
        if (recovered.compareAndSet(false, true)) {
            threadPool.generic().execute(new Runnable() {
                @Override
                public void run() {
                    gateway.performStateRecovery(recoveryListener);
                }
            });
        }
        */
    }

    class GatewayRecoveryListener implements Gateway.GatewayStateRecoveredListener {

        @Override
        public void onSuccess(final ClusterState recoveredState) {
            logger.trace("successful state recovery, importing cluster state...");
            clusterService.submitStateUpdateTask("local-gateway-elected-state", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    assert currentState.metaData().indices().isEmpty();
                    
                    // remove the block, since we recovered from gateway
                    ClusterBlocks.Builder blocks = ClusterBlocks.builder()
                            .blocks(currentState.blocks())
                            .blocks(recoveredState.blocks())
                            .removeGlobalBlock(STATE_NOT_RECOVERED_BLOCK);

                    MetaData.Builder metaDataBuilder = MetaData.builder(recoveredState.metaData());
                    // automatically generate a UID for the metadata if we need to
                    metaDataBuilder.generateClusterUuidIfNeeded();

                    if (recoveredState.metaData().settings().getAsBoolean(MetaData.SETTING_READ_ONLY, false) || currentState.metaData().settings().getAsBoolean(MetaData.SETTING_READ_ONLY, false)) {
                        blocks.addGlobalBlock(MetaData.CLUSTER_READ_ONLY_BLOCK);
                    }

                    for (IndexMetaData indexMetaData : recoveredState.metaData()) {
                        metaDataBuilder.put(indexMetaData, false);
                        blocks.addBlocks(indexMetaData);
                    }
                    
                    
                    // update the state to reflect the new metadata and routing
                    ClusterState updatedState = ClusterState.builder(currentState)
                            .blocks(blocks)
                            .metaData(metaDataBuilder)
                            .build();

                    // initialize all index routing tables as empty
                    RoutingTable routingTable = RoutingTable.build(CassandraGatewayService.this.clusterService, updatedState);
                    return ClusterState.builder(updatedState).incrementVersion().routingTable(routingTable).build();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    logger.error("unexpected failure during [{}]", t, source);
                    GatewayRecoveryListener.this.onFailure("failed to updated cluster state");
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    logger.info("recovered [{}] indices into cluster_state", newState.metaData().indices().size());
                }
            });
        }

        @Override
        public void onFailure(String message) {
            recovered.set(false);
            // don't remove the block here, we don't want to allow anything in such a case
            logger.info("metadata state not restored, reason: {}", message);
        }

    }
}
