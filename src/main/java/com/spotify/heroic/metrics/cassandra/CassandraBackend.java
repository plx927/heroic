package com.spotify.heroic.metrics.cassandra;

import java.util.concurrent.atomic.AtomicReference;

import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import com.netflix.astyanax.AstyanaxConfiguration;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;
import com.spotify.heroic.metrics.Backend;

/**
 * A partial and generic backend implementation for cassandra-based metric
 * backends.
 *
 * The keyspace should be accessed using the {@link CassandraBackend#keyspace}
 * method.
 *
 * @author udoprog
 */
@Slf4j
@RequiredArgsConstructor
@ToString
public abstract class CassandraBackend implements Backend {
    private final String id;
    private final String keyspaceName;
    private final String seeds;
    private final int maxConnectionsPerHost;

    private AstyanaxContext<Keyspace> context;
    // could be volatile, but this asserts that a request has to fetch-and-store
    // the keyspace _once_ in the context for which it is valid.
    private final AtomicReference<Keyspace> keyspace = new AtomicReference<Keyspace>();

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isReady() {
        return keyspace() != null;
    }

    protected Keyspace keyspace() {
        return keyspace.get();
    }

    @Override
    public void start() throws Exception {
        log.info("Starting: {}", this);

        final AstyanaxConfiguration config = new AstyanaxConfigurationImpl()
                .setCqlVersion("3.0.0").setTargetCassandraVersion("2.0");

        context = new AstyanaxContext.Builder()
                .withConnectionPoolConfiguration(
                        new ConnectionPoolConfigurationImpl(
                                "HeroicConnectionPool").setPort(9160)
                                .setMaxConnsPerHost(maxConnectionsPerHost)
                                .setSeeds(seeds)).forKeyspace(keyspaceName)
                .withAstyanaxConfiguration(config)
                .buildKeyspace(ThriftFamilyFactory.getInstance());

        context.start();
        keyspace.set(context.getClient());
    }

    @Override
    public void stop() throws Exception {
        log.info("Stopping: {}", this);

        context.shutdown();
        keyspace.set(null);
    }
}
