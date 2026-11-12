package com.mymicroservice.product.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;

// 索引名稱由呼叫端提供，讓重建流程能先建立新索引再切換別名。
@Component
public class ProductIndexManager {
    public static final String ALIAS = "products";
    public static final int SCHEMA_VERSION = 1;

    private final ElasticsearchClient client;

    public ProductIndexManager(ElasticsearchClient client) {
        this.client = client;
    }

    public void createIndex(String indexName, String alias) throws IOException {
        try (InputStream stream = new ClassPathResource("search/products-index.json").getInputStream()) {
            client.indices().create(c -> c.index(indexName).withJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        if (alias != null && !alias.isBlank()) {
            client.indices().putAlias(a -> a.index(indexName).name(alias).isWriteIndex(true));
        }
    }

    public boolean indexExists(String indexName) throws IOException {
        return client.indices().exists(e -> e.index(indexName)).value();
    }

    public boolean isBuilt(String indexName) throws IOException {
        GetMappingResponse response = client.indices().getMapping(g -> g.index(indexName));
        IndexMappingRecord index = response.get(indexName);
        return index != null && index.mappings().meta() != null
                && Boolean.TRUE.equals(index.mappings().meta().get("built").to(Boolean.class));
    }

    public int schemaVersion(String indexName) throws IOException {
        GetMappingResponse response = client.indices().getMapping(g -> g.index(indexName));
        IndexMappingRecord index = response.get(indexName);
        if (index == null || index.mappings().meta() == null) {
            return -1;
        }
        JsonData value = index.mappings().meta().get("schemaVersion");
        Integer version = value == null ? null : value.to(Integer.class);
        return version == null ? -1 : version;
    }

    public Set<String> indicesForAlias(String alias) throws IOException {
        if (!client.indices().existsAlias(a -> a.name(alias)).value()) {
            return java.util.Set.of();
        }
        return client.indices().getAlias(a -> a.name(alias)).aliases().keySet();
    }

    public void switchAlias(String alias, String newIndex, Set<String> oldIndices) throws IOException {
        client.indices().updateAliases(a -> {
            for (String oldIndex : oldIndices) {
                a.actions(action -> action.remove(remove -> remove.index(oldIndex).alias(alias)));
            }
            return a.actions(action -> action.add(add -> add.index(newIndex).alias(alias).isWriteIndex(true)));
        });
    }

    public void deleteIndex(String indexName) throws IOException {
        client.indices().delete(d -> d.index(indexName));
    }

    public void bulkWrite(String indexName, List<ProductDocument> documents, List<Long> versions) throws IOException {
        if (documents.size() != versions.size()) {
            throw new IllegalArgumentException("Each product document must have one external version");
        }
        BulkResponse response = client.bulk(b -> {
            for (int i = 0; i < documents.size(); i++) {
                ProductDocument document = documents.get(i);
                long version = versions.get(i);
                b.operations(operation -> operation.index(index -> index
                        .index(indexName)
                        .id(document.id())
                        .document(document)
                        .version(version)
                        .versionType(VersionType.ExternalGte)));
            }
            return b;
        });
        for (var item : response.items()) {
            if (item.error() != null && item.status() != 409) {
                throw new IOException("Elasticsearch bulk indexing failed for " + item.id() + ": "
                        + item.error().reason());
            }
        }
    }

    public void markBuilt(String indexName, boolean built) throws IOException {
        client.indices().putMapping(p -> p.index(indexName)
                .meta(Map.of("schemaVersion", JsonData.of(SCHEMA_VERSION), "built", JsonData.of(built))));
    }

    public long write(ProductDocument document, long version) throws IOException {
        return write(ALIAS, document, version);
    }

    public long write(String target, ProductDocument document, long version) throws IOException {
        try {
            IndexResponse response = client.index(i -> {
                i.index(target)
                        .id(document.id())
                        .document(document)
                        .version(version)
                        .versionType(VersionType.ExternalGte);
                if (ALIAS.equals(target)) {
                    i.requireAlias(true);
                }
                return i;
            });
            return response.version();
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                return -1;
            }
            throw e;
        }
    }
}
