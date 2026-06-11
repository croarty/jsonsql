package com.jsonsql.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IndexBuilder}: value extraction, kind detection, min/max,
 * value typing, and the high-cardinality guard.
 */
class IndexBuilderTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private IndexStore store;
    private IndexBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        store = new IndexStore(tempDir);
        builder = new IndexBuilder(mappingManager, tempDir, store);

        // Partitioned dataset in a nested directory tree.
        writeFile("products-multi/2023/p.json", """
            { "products": [
              {"id": 1, "category": "Tools", "price": 9.99,  "tags": ["a","b"],
               "reviews": [{"user":"x","rating":3}]},
              {"id": 2, "category": "Electronics", "price": 29.99, "tags": ["b","c"],
               "reviews": [{"user":"y","rating":4},{"user":"z","rating":3}]}
            ]}""");
        writeFile("products-multi/2024/p.json", """
            { "products": [
              {"id": 3, "category": "Furniture", "price": 50.0,  "tags": ["d"],
               "reviews": [{"user":"q","rating":5}]},
              {"id": 4, "category": "Office", "price": 120.0, "tags": ["d","e"],
               "reviews": [{"user":"r","rating":5}]}
            ]}""");
        mappingManager.addMapping("products", "products-multi:$.products[*]");
    }

    private void writeFile(String relPath, String content) throws IOException {
        File f = new File(tempDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private FileSummary summaryContaining(TableIndex idx, String pathFragment) {
        for (FileSummary s : idx.getFiles()) {
            if (s.getRelPath().contains(pathFragment)) {
                return s;
            }
        }
        fail("No file summary matching: " + pathFragment);
        return null;
    }

    private boolean containsValue(List<JsonNode> values, String text) {
        for (JsonNode v : values) {
            if (v.asText().equals(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void buildsScalarIndexWithDistinctAndMinMax() throws IOException {
        TableIndex idx = builder.build("products", "category");

        assertEquals(TableIndex.KIND_SCALAR, idx.getKind());
        assertEquals(2, idx.getFiles().size());

        FileSummary s2023 = summaryContaining(idx, "2023");
        assertEquals("string", s2023.getValueType());
        assertEquals(2, s2023.getDistinctValues().size());
        assertTrue(containsValue(s2023.getDistinctValues(), "Tools"));
        assertTrue(containsValue(s2023.getDistinctValues(), "Electronics"));
        assertEquals("Electronics", s2023.getMin().asText());
        assertEquals("Tools", s2023.getMax().asText());

        FileSummary s2024 = summaryContaining(idx, "2024");
        assertFalse(containsValue(s2024.getDistinctValues(), "Tools"));
        assertTrue(containsValue(s2024.getDistinctValues(), "Furniture"));
    }

    @Test
    void buildsNumericScalarIndexMinMax() throws IOException {
        TableIndex idx = builder.build("products", "price");

        assertEquals(TableIndex.KIND_SCALAR, idx.getKind());
        FileSummary s2023 = summaryContaining(idx, "2023");
        assertEquals("number", s2023.getValueType());
        assertEquals(9.99, s2023.getMin().asDouble(), 0.0001);
        assertEquals(29.99, s2023.getMax().asDouble(), 0.0001);

        FileSummary s2024 = summaryContaining(idx, "2024");
        assertEquals(50.0, s2024.getMin().asDouble(), 0.0001);
        assertEquals(120.0, s2024.getMax().asDouble(), 0.0001);
    }

    @Test
    void buildsMultivaluedIndexForArrayOfStrings() throws IOException {
        TableIndex idx = builder.build("products", "tags");

        assertEquals(TableIndex.KIND_MULTIVALUED, idx.getKind());
        FileSummary s2023 = summaryContaining(idx, "2023");
        // union of ["a","b"] and ["b","c"] = a,b,c
        assertEquals(3, s2023.getDistinctValues().size());
        assertTrue(containsValue(s2023.getDistinctValues(), "a"));
        assertTrue(containsValue(s2023.getDistinctValues(), "c"));

        FileSummary s2024 = summaryContaining(idx, "2024");
        assertTrue(containsValue(s2024.getDistinctValues(), "d"));
        assertFalse(containsValue(s2024.getDistinctValues(), "a"));
    }

    @Test
    void buildsMultivaluedIndexForArrayOfObjectSubProperty() throws IOException {
        TableIndex idx = builder.build("products", "reviews.rating");

        assertEquals(TableIndex.KIND_MULTIVALUED, idx.getKind());
        FileSummary s2023 = summaryContaining(idx, "2023");
        assertEquals("number", s2023.getValueType());
        // ratings 3,4,3 -> distinct {3,4}, min 3 max 4
        assertEquals(3.0, s2023.getMin().asDouble(), 0.0001);
        assertEquals(4.0, s2023.getMax().asDouble(), 0.0001);

        FileSummary s2024 = summaryContaining(idx, "2024");
        assertEquals(5.0, s2024.getMin().asDouble(), 0.0001);
        assertEquals(5.0, s2024.getMax().asDouble(), 0.0001);
    }

    @Test
    void persistsAndReloadsIndex() throws IOException {
        builder.build("products", "category");
        TableIndex reloaded = store.load("products", "category");
        assertNotNull(reloaded);
        assertEquals("products", reloaded.getTable());
        assertEquals("category", reloaded.getField());
        assertEquals(2, reloaded.getFiles().size());
        assertEquals("products-multi:$.products[*]".substring("products-multi:".length()),
            reloaded.getJsonPath());
    }

    @Test
    void emptyValueTypeWhenFieldMissing() throws IOException {
        TableIndex idx = builder.build("products", "nonexistent");
        FileSummary s = idx.getFiles().get(0);
        assertEquals("empty", s.getValueType());
        assertTrue(s.getDistinctValues() == null || s.getDistinctValues().isEmpty());
    }

    @Test
    void highCardinalityDropsValuesButKeepsMinMax() throws IOException {
        StringBuilder sb = new StringBuilder("{ \"items\": [");
        for (int i = 0; i < IndexBuilder.CARDINALITY_LIMIT + 50; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\": ").append(i).append("}");
        }
        sb.append("] }");
        writeFile("big/data.json", sb.toString());
        mappingManager.addMapping("big", "big:$.items[*]");

        TableIndex idx = builder.build("big", "id");
        FileSummary s = idx.getFiles().get(0);
        assertTrue(s.isValuesOmitted(), "values should be omitted above the cardinality limit");
        assertNull(s.getDistinctValues());
        assertNotNull(s.getMin());
        assertNotNull(s.getMax());
        assertEquals(0.0, s.getMin().asDouble(), 0.0001);
        assertEquals((double) (IndexBuilder.CARDINALITY_LIMIT + 49), s.getMax().asDouble(), 0.0001);
    }
}
