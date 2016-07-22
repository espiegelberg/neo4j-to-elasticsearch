package com.graphaware.module.es;

import com.graphaware.common.policy.all.IncludeAllRelationships;
import com.graphaware.integration.es.test.EmbeddedElasticSearchServer;
import com.graphaware.integration.es.test.JestElasticSearchClient;
import com.graphaware.module.es.mapping.JsonFileMapping;
import com.graphaware.module.es.mapping.Mapping;
import com.graphaware.module.es.util.ServiceLoader;
import com.graphaware.module.es.util.TestUtil;
import com.graphaware.module.uuid.UuidConfiguration;
import com.graphaware.module.uuid.UuidModule;
import com.graphaware.runtime.GraphAwareRuntime;
import com.graphaware.runtime.GraphAwareRuntimeFactory;
import io.searchbox.client.JestResult;
import io.searchbox.core.Get;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.core.NodeProxy;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElasticSearchModuleJsonMappingTest extends ElasticSearchModuleIntegrationTest {

    @Before
    public void setUp() {
        esServer = new EmbeddedElasticSearchServer();
        esServer.start();
        esClient = new JestElasticSearchClient(HOST, PORT);

    }

    @After
    public void tearDown() {
        database.shutdown();
        esServer.stop();
        esClient.shutdown();
    }

    @Test
    public void testBasicJsonMappingModuleBootstrap() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration(), database));

        Mapping mapping = ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-basic.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        assertEquals("uuid", configuration.getMapping().getKeyProperty());
        assertEquals("default-index-node", ((JsonFileMapping)configuration.getMapping()).getMappingRepresentation().getDefaults().getDefaultNodesIndex());
        assertEquals("default-index-relationship", ((JsonFileMapping)configuration.getMapping()).getMappingRepresentation().getDefaults().getDefaultRelationshipsIndex());
    }

    @Test
    public void testBasicJsonMappingReplication() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid").with(IncludeAllRelationships.getInstance()), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-basic.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .with(IncludeAllRelationships.getInstance())
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();

        writeSomePersons();
        TestUtil.waitFor(1000);
        verifyEsReplicationForNodeWithLabels("Person", mapping.getMappingRepresentation().getDefaults().getDefaultNodesIndex(), "persons", mapping.getMappingRepresentation().getDefaults().getKeyProperty());
        try (Transaction tx = database.beginTx()) {
            database.getAllRelationships().stream().forEach(r -> {
                new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(r, mapping.getMappingRepresentation().getDefaults().getDefaultRelationshipsIndex(), "workers", mapping.getKeyProperty());
            });
            tx.success();
        }
    }

    @Test
    public void testJsonMappingWithMultipleMappingsAndMoreThanOneLabelAndIndex() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-multi-labels.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();

        writeSomePersons();
        TestUtil.waitFor(1000);
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label("Female")).stream().forEach(n -> {
                new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, "females", "girls", mapping.getKeyProperty());
            });
            database.findNodes(Label.label("Person")).stream().forEach(n -> {
                new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, mapping.getMappingRepresentation().getDefaults().getDefaultNodesIndex(), "persons", mapping.getKeyProperty());
            });
            tx.success();
        }
    }

    @Test
    public void testShouldReplicateNodesWithoutLabels() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-advanced.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();

        writeSomePersons();
        TestUtil.waitFor(1000);
        try (Transaction tx = database.beginTx()) {
            database.getAllNodes().stream()
                    .filter(n -> {
                return labelsToStrings(n).size() == 0;
            })
                    .forEach(n -> {
                        new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, mapping.getMappingRepresentation().getDefaults().getDefaultNodesIndex(), "nodes-without-labels", mapping.getKeyProperty());
                    });
            tx.success();
        }
    }

    @Test
    public void testNodesWithArrayPropertyValuesShouldBeReplicatedCorrectly() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-advanced.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();
        database.execute("CREATE (n:Node {types:['a','b','c']})");
        TestUtil.waitFor(1500);
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label("Node")).stream().forEach(n -> {
                JestResult result = new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, mapping.getMappingRepresentation().getDefaults().getDefaultNodesIndex(), "nodes", mapping.getKeyProperty());
                Map<String, Object> source = new HashMap<>();
                List<String> types = (List<String>) result.getSourceAsObject(source.getClass()).get("types");
                assertEquals(3, types.size());
                assertEquals("a", types.get(0));
                assertEquals("c", types.get(2));
            });
            tx.success();
        }
    }

    @Test
    public void testNodesWithMultipleLabelsAreUpdatedCorrectly() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-advanced.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();
        writeSomePersons();
        TestUtil.waitFor(1000);
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label("Person")).stream()
                    .filter(n -> { return n.hasLabel(Label.label("Female"));})
                    .forEach(n -> {
                        new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, "females", "girls", mapping.getKeyProperty());
                    });
            tx.success();
        }

        database.execute("MATCH (n:Female) REMOVE n:Female SET n:Node");
        TestUtil.waitFor(1000);
        verifyNoEsReplicationForNodesWithLabel("Person", "females", "girls", mapping.getKeyProperty());


    }

    @Test
    public void testDeleteNodesAreDeletedFromIndices() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-advanced.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();
        database.execute("CREATE (n:Person {name:\"Person1\"}), (:Person {name:\"Person2\"})");
        TestUtil.waitFor(1000);
        List<String> ids = new ArrayList<>();
        try (Transaction tx = database.beginTx()) {
            Iterator<Node> nodes = database.findNodes(Label.label("Person"));
            while (nodes.hasNext()) {
                Node n = nodes.next();
                ids.add(n.getProperty("uuid").toString());
            }

            tx.success();
        }

        verifyEsReplicationForNodeWithLabels("Person", ((JsonFileMapping) configuration.getMapping()).getMappingRepresentation().getDefaults().getDefaultNodesIndex(), "persons", configuration.getMapping().getKeyProperty());
        database.execute("MATCH (n) DETACH DELETE n");
        TestUtil.waitFor(1000);
        for (String id : ids) {
            String index = ((JsonFileMapping) configuration.getMapping()).getMappingRepresentation().getDefaults().getDefaultNodesIndex();
            String type = "persons";
            Get get = new Get.Builder(index, id).type(type).build();
            JestResult result = esClient.execute(get);

            assertTrue(!result.isSucceeded() || !((Boolean) result.getValue("found")));
        }

    }

    protected void verifyEsReplicationForNodeWithLabels(String label, String index, String type, String keyProperty) {
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label(label)).stream().forEach(n -> {
                new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, index, type, keyProperty);
            });
            tx.success();
        }
    }

    protected void verifyNoEsReplicationForNodesWithLabel(String label, String index, String type, String keyProperty) {
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label(label)).stream()
                    .forEach(n -> {
                        new Neo4jElasticVerifier(database, configuration, esClient).verifyNoEsReplication(n, index, type, keyProperty);
                    });
            tx.success();
        }
    }

    protected void writeSomePersons() {
        //tx 0
        database.execute("CREATE (n {name:'Hello'})");

        //tx1
        database.execute("CREATE (p:Person:Male {firstName:'Michal', lastName:'Bachman', age:30})-[:WORKS_FOR {since:2013, role:'MD'}]->(c:Company {name:'GraphAware', est: 2013})");

        //tx2
        database.execute("MATCH (ga:Company {name:'GraphAware'}) CREATE (p:Person:Male {firstName:'Adam', lastName:'George'})-[:WORKS_FOR {since:2014}]->(ga)");

        //tx3
        try (Transaction tx = database.beginTx()) {
            database.execute("MATCH (ga:Company {name:'GraphAware'}) CREATE (p:Person:Female {firstName:'Daniela', lastName:'Daniela'})-[:WORKS_FOR]->(ga)");
            database.execute("MATCH (p:Person {name:'Michal'}) SET p.age=31");
            database.execute("MATCH (p:Person {name:'Adam'})-[r]-() DELETE p,r");
            database.execute("MATCH (p:Person {name:'Michal'})-[r:WORKS_FOR]->() REMOVE r.role");
            tx.success();
        }
    }

}