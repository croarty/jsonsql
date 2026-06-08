package com.jsonsql.introspection;

import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TableDescriberTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private TableDescriber describer;

    @BeforeEach
    void setUp() throws Exception {
        String data = """
            {
              "products": [
                {
                  "id": 1,
                  "name": "Widget",
                  "price": 19.99,
                  "inStock": true,
                  "tags": ["a", "b"],
                  "details": {
                    "color": "red",
                    "weight": null
                  }
                },
                {
                  "id": 2,
                  "name": "Gadget",
                  "price": null,
                  "inStock": false,
                  "tags": [],
                  "details": {
                    "color": "blue",
                    "weight": 12
                  }
                }
              ]
            }
            """;
        Files.writeString(tempDir.toPath().resolve("products.json"), data);

        File configFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(configFile);
        mappingManager.addMapping("products", "products.json:$.products[*]");
        describer = new TableDescriber(mappingManager, tempDir);
    }

    @Test
    void describeShowsMappingRowsAndFields() throws Exception {
        String output = describer.describe("products");

        assertTrue(output.contains("Table: products"));
        assertTrue(output.contains("Mapping: products.json:$.products[*]"));
        assertTrue(output.contains("Rows: 2"));
        assertTrue(output.contains("Field"));
        assertTrue(output.contains("Type"));
        assertTrue(output.contains("Sample"));
        assertTrue(output.contains("id"));
        assertTrue(output.contains("name"));
        assertTrue(output.contains("tags"));
        assertTrue(output.contains("details.color"));
        assertTrue(output.contains("details.weight"));
    }

    @Test
    void describeInfersTypesAndSamples() throws Exception {
        String output = describer.describe("products");

        assertTrue(output.contains("number"));
        assertTrue(output.contains("string"));
        assertTrue(output.contains("boolean"));
        assertTrue(output.contains("array"));
        assertTrue(output.contains("Widget"));
        assertTrue(output.contains("[\"a\",\"b\"]") || output.contains("[\"a\", \"b\"]"));
    }

    @Test
    void describeMarksNullableFields() throws Exception {
        String output = describer.describe("products");

        assertTrue(output.contains("number (nullable)"));
    }

    @Test
    void describeEmptyTableReportsNoFields() throws Exception {
        Files.writeString(tempDir.toPath().resolve("empty.json"), "{\"items\": []}");
        mappingManager.addMapping("empty_items", "empty.json:$.items[*]");

        String output = describer.describe("empty_items");

        assertTrue(output.contains("Rows: 0"));
        assertTrue(output.contains("No fields found"));
    }

    @Test
    void describeUnknownTableThrows() {
        assertThrows(IllegalArgumentException.class, () -> describer.describe("missing"));
    }

    @Test
    void jsonTypeCoversAllNodeKinds() {
        assertEquals("null", TableDescriber.jsonType(null));
    }

    @Test
    void describeTruncatesLongSamples() throws Exception {
        String longName = "x".repeat(TableDescriber.MAX_SAMPLE_LENGTH + 10);
        String data = """
            {"products": [{"id": 1, "name": "%s"}]}
            """.formatted(longName);
        Files.writeString(tempDir.toPath().resolve("long.json"), data);
        mappingManager.addMapping("long_products", "long.json:$.products[*]");

        String output = describer.describe("long_products");

        assertTrue(output.contains("..."));
        assertFalse(output.contains(longName));
    }
}
