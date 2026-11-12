package com.mymicroservice.product.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.repository.ProductRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ProductIndexRebuildService {
    private static final Logger log = LoggerFactory.getLogger(ProductIndexRebuildService.class);
    static final long ADVISORY_LOCK_KEY = 7_318_204_992L;
    private static final Duration SUPPLEMENT_WINDOW = Duration.ofMinutes(5);
    private static final DateTimeFormatter INDEX_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository productRepository;
    private final ProductIndexManager indexManager;
    private final MeterRegistry meterRegistry;

    public ProductIndexRebuildService(JdbcTemplate jdbcTemplate, ProductRepository productRepository,
            ProductIndexManager indexManager, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
        this.indexManager = indexManager;
        this.meterRegistry = meterRegistry;
    }

    public RebuildResult rebuild(boolean deleteOld) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startedNanos = System.nanoTime();
        try {
            RebuildResult result = jdbcTemplate.execute((ConnectionCallback<RebuildResult>) connection -> {
                if (!tryLock(connection)) {
                    log.info("Product index rebuild skipped because another instance holds the advisory lock");
                    return new RebuildResult(false, null, 0, 0, false, 0);
                }
                try {
                    try {
                        return rebuildWithLock(Instant.now(), deleteOld);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                } finally {
                    unlock(connection);
                }
            });
            return result;
        } catch (RuntimeException failure) {
            meterRegistry.counter("search.rebuild.failures").increment();
            log.error("Product index rebuild failed", failure);
            throw failure;
        } finally {
            sample.stop(meterRegistry.timer("search.rebuild.duration"));
            log.info("Product index rebuild finished in {} ms", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
        }
    }

    public boolean isCurrentIndexReady() throws IOException {
        Set<String> indices = indexManager.indicesForAlias(ProductIndexManager.ALIAS);
        if (indices.size() != 1) {
            return false;
        }
        String index = indices.iterator().next();
        return indexManager.schemaVersion(index) == ProductIndexManager.SCHEMA_VERSION
                && indexManager.isBuilt(index);
    }

    private RebuildResult rebuildWithLock(Instant startedAt, boolean deleteOld) throws IOException {
        Instant rebuildStartedAt = Instant.now();
        Set<String> oldIndices = indexManager.indicesForAlias(ProductIndexManager.ALIAS);
        String indexName = "products-" + INDEX_TIME.format(rebuildStartedAt) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        boolean aliasSwitched = false;
        boolean supplementCompleted = false;
        try {
            indexManager.createIndex(indexName, null);

            long indexed = writeKeysetBatches(indexName);
            indexManager.markBuilt(indexName, true);
            indexManager.switchAlias(ProductIndexManager.ALIAS, indexName, oldIndices);
            aliasSwitched = true;

            long supplemented = writeUpdatedSince(indexName, startedAt.minus(SUPPLEMENT_WINDOW));
            supplementCompleted = true;
            long total = indexed + supplemented;

            boolean oldDeleted = false;
            if (deleteOld) {
                for (String oldIndex : oldIndices) {
                    indexManager.deleteIndex(oldIndex);
                    oldDeleted = true;
                }
            }
            log.info("Product index rebuild switched alias to {}: {} scanned, {} supplemented, {} total writes",
                    indexName, indexed, supplemented, total);
            return new RebuildResult(true, indexName, indexed, supplemented, oldDeleted,
                    Duration.between(startedAt, Instant.now()).toMillis());
        } catch (IOException | RuntimeException failure) {
            if (!aliasSwitched) {
                try {
                    if (indexManager.indexExists(indexName)) {
                        indexManager.deleteIndex(indexName);
                    }
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            } else if (!supplementCompleted) {
                try {
                    indexManager.markBuilt(indexName, false);
                } catch (IOException readinessFailure) {
                    failure.addSuppressed(readinessFailure);
                }
            }
            throw failure;
        }
    }

    private long writeKeysetBatches(String targetIndex) throws IOException {
        UUID lastId = null;
        long count = 0;
        while (true) {
            List<Product> products = lastId == null
                    ? productRepository.findTop500ByOrderByIdAsc()
                    : productRepository.findTop500ByIdGreaterThanOrderByIdAsc(lastId);
            if (products.isEmpty()) {
                return count;
            }
            writeProducts(targetIndex, products);
            count += products.size();
            lastId = products.get(products.size() - 1).getId();
            log.info("Product index rebuild indexed {} database rows", count);
            if (products.size() < ProductRepository.INDEX_BATCH_SIZE) {
                return count;
            }
        }
    }

    private long writeUpdatedSince(String targetIndex, Instant updatedSince) throws IOException {
        UUID lastId = null;
        long count = 0;
        while (true) {
            List<Product> products = lastId == null
                    ? productRepository.findTop500ByUpdatedAtGreaterThanEqualOrderByIdAsc(updatedSince)
                    : productRepository.findTop500ByUpdatedAtGreaterThanEqualAndIdGreaterThanOrderByIdAsc(
                            updatedSince, lastId);
            if (products.isEmpty()) {
                return count;
            }
            writeProducts(targetIndex, products);
            count += products.size();
            lastId = products.get(products.size() - 1).getId();
            if (products.size() < ProductRepository.INDEX_BATCH_SIZE) {
                return count;
            }
        }
    }

    private void writeProducts(String targetIndex, List<Product> products) throws IOException {
        List<ProductDocument> documents = new ArrayList<>(products.size());
        List<Long> versions = new ArrayList<>(products.size());
        for (Product product : products) {
            documents.add(ProductDocument.from(product));
            versions.add(product.getVersion());
        }
        indexManager.bulkWrite(targetIndex, documents, versions);
        meterRegistry.counter("search.rebuild.documents").increment(products.size());
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            statement.execute();
        } catch (SQLException failure) {
            log.error("Could not release product index rebuild advisory lock", failure);
        }
    }

    public record RebuildResult(boolean lockAcquired, String indexName, long scannedDocuments,
            long supplementedDocuments, boolean oldIndicesDeleted, long durationMillis) {
    }
}
