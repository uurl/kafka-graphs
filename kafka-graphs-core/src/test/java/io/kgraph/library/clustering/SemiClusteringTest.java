/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.kgraph.library.clustering;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.serialization.DoubleSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kgraph.AbstractIntegrationTest;
import io.kgraph.Edge;
import io.kgraph.GraphAlgorithm;
import io.kgraph.GraphAlgorithmState;
import io.kgraph.GraphSerialized;
import io.kgraph.KGraph;
import io.kgraph.library.clustering.SemiClustering.SemiCluster;
import io.kgraph.pregel.PregelGraphAlgorithm;
import io.kgraph.utils.ClientUtils;
import io.kgraph.utils.GraphUtils;
import io.kgraph.utils.KryoSerde;
import io.kgraph.utils.KryoSerializer;
import io.kgraph.utils.StreamUtils;

public class SemiClusteringTest extends AbstractIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(SemiClusteringTest.class);

    GraphAlgorithm<Long, Set<SemiCluster>, Double, KTable<Long, Set<SemiCluster>>> algorithm;

    @Test
    public void testSemiClustering() throws Exception {
        String suffix = "";
        StreamsBuilder builder = new StreamsBuilder();

        List<KeyValue<Edge<Long>, Double>> list = new ArrayList<>();
        list.add(new KeyValue<>(new Edge<>(1L, 2L), 1.0));
        list.add(new KeyValue<>(new Edge<>(2L, 1L), 1.0));
        list.add(new KeyValue<>(new Edge<>(1L, 3L), 1.0));
        list.add(new KeyValue<>(new Edge<>(3L, 1L), 1.0));
        list.add(new KeyValue<>(new Edge<>(2L, 3L), 2.0));
        list.add(new KeyValue<>(new Edge<>(3L, 2L), 2.0));
        list.add(new KeyValue<>(new Edge<>(3L, 4L), 2.0));
        list.add(new KeyValue<>(new Edge<>(4L, 3L), 2.0));
        list.add(new KeyValue<>(new Edge<>(3L, 5L), 1.0));
        list.add(new KeyValue<>(new Edge<>(5L, 3L), 1.0));
        list.add(new KeyValue<>(new Edge<>(4L, 5L), 1.0));
        list.add(new KeyValue<>(new Edge<>(5L, 4L), 1.0));
        Properties producerConfig = ClientUtils.producerConfig(CLUSTER.bootstrapServers(), KryoSerializer.class,
            DoubleSerializer.class, new Properties()
        );
        KTable<Edge<Long>, Double> edges =
            StreamUtils.tableFromCollection(builder, producerConfig, new KryoSerde<>(), Serdes.Double(), list);
        KGraph<Long, Set<SemiCluster>, Double> graph = KGraph.fromEdges(edges, new InitVertices(),
            GraphSerialized.with(Serdes.Long(), new KryoSerde<>(), Serdes.Double()));

        Properties props = ClientUtils.streamsConfig("prepare-" + suffix, "prepare-client-" + suffix,
            CLUSTER.bootstrapServers(), graph.keySerde().getClass(), graph.vertexValueSerde().getClass());
        CompletableFuture<Void> state = GraphUtils.groupEdgesBySourceAndRepartition(builder, props, graph, "vertices-" + suffix, "edgesGroupedBySource-" + suffix, 2, (short) 1);
        state.get();

        Map<String, Object> configs = new HashMap<>();
        configs.put(SemiClustering.ITERATIONS, 10);
        configs.put(SemiClustering.MAX_CLUSTERS, 2);
        configs.put(SemiClustering.CLUSTER_CAPACITY, 2);
        algorithm =
            new PregelGraphAlgorithm<>(null, "run-" + suffix, CLUSTER.bootstrapServers(),
                CLUSTER.zKConnectString(), "vertices-" + suffix, "edgesGroupedBySource-" + suffix, graph.serialized(),
                "solutionSet-" + suffix, "solutionSetStore-" + suffix, "workSet-" + suffix, 2, (short) 1,
                configs, Optional.empty(), new SemiClustering());
        streamsConfiguration = ClientUtils.streamsConfig("run-" + suffix, "run-client-" + suffix,
            CLUSTER.bootstrapServers(), graph.keySerde().getClass(), KryoSerde.class);
        KafkaStreams streams = algorithm.configure(new StreamsBuilder(), streamsConfiguration).streams();
        GraphAlgorithmState<KTable<Long, Set<SemiCluster>>> paths = algorithm.run();
        paths.result().get();

        Map<Long, Map<Long, Long>> map = StreamUtils.mapFromStore(paths.streams(), "solutionSetStore-" + suffix);
        log.debug("result: {}", map);

        assertEquals("{1=[[ 1 4  | -2.5, 0.0, 5.0 ], [ 1 3  | -2.0, 1.0, 6.0 ]], 2=[[ 2 4  | -3.0, 0.0, 6.0 ], [ 2 3  | -0.5, 2.0, 5.0 ]], 3=[[ 3 4  | -0.5, 2.0, 5.0 ], [ 3  | 0.0, 0.0, 6.0 ]], 4=[[ 3 4  | -0.5, 2.0, 5.0 ], [ 4  | 0.0, 0.0, 3.0 ]], 5=[[ 3 5  | -2.0, 1.0, 6.0 ], [ 4 5  | -0.5, 1.0, 3.0 ]]}", map.toString());
    }

    @After
    public void tearDown() throws Exception {
        algorithm.close();
    }

    private static final class InitVertices implements ValueMapper<Long, Set<SemiCluster>> {
        @Override
        public Set<SemiCluster> apply(Long id) {
            return new TreeSet<>();
        }
    }
}
