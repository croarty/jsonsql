package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Constructs that JSqlParser accepts but the engine does not implement must be rejected at
 * parse time with a clear error, instead of silently producing wrong results.
 */
class UnsupportedSyntaxTest {

    @TempDir
    Path tempDir;

    private final QueryParser parser = new QueryParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        File dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        Files.writeString(dataDir.toPath().resolve("products.json"), """
            {"products": [
              {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools", "tags": ["a", "b"]},
              {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics", "tags": ["b"]}
            ]}
            """);
        Files.writeString(dataDir.toPath().resolve("orders.json"), """
            {"orders": [{"orderId": 10, "productId": 1}, {"orderId": 11, "productId": 3}]}
            """);
        MappingManager mappingManager = new MappingManager(tempDir.resolve("mappings.json").toFile());
        mappingManager.addMapping("products", "products.json:$.products");
        mappingManager.addMapping("orders", "orders.json:$.orders");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private QueryParseException rejected(String sql) {
        return assertThrows(QueryParseException.class, () -> parser.parse(sql), sql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "SELECT name FROM products OFFSET 5 ROWS FETCH NEXT 2 ROWS ONLY         | FETCH is not supported",
        "SELECT o.orderId FROM orders o RIGHT JOIN products p ON o.productId = p.id      | RIGHT JOIN is not supported",
        "SELECT o.orderId FROM orders o FULL OUTER JOIN products p ON o.productId = p.id | FULL OUTER JOIN is not supported",
        "SELECT o.orderId FROM orders o FULL JOIN products p ON o.productId = p.id       | FULL OUTER JOIN is not supported",
        "SELECT o.orderId FROM orders o CROSS JOIN products p                   | CROSS JOIN is not supported",
        "SELECT o.orderId FROM orders o NATURAL JOIN products p                 | NATURAL JOIN is not supported",
        "SELECT p.* FROM products p                                             | SELECT p.* is not supported",
        "SELECT UPPER(name) FROM products                                       | Function or aggregate in SELECT list: UPPER(name) is not supported",
        "SELECT price * 2 AS doubled FROM products                              | Expression in SELECT list: price * 2 is not supported",
        "SELECT 'x' AS label FROM products                                      | Expression in SELECT list: 'x' is not supported",
        "SELECT 5 FROM products                                                 | Expression in SELECT list: 5 is not supported",
        "SELECT CASE WHEN price > 20 THEN 'hi' ELSE 'lo' END FROM products     | Expression in SELECT list: CASE",
        "SELECT name FROM products INTERSECT SELECT name FROM products          | INTERSECT is not supported",
        "SELECT name FROM products EXCEPT SELECT name FROM products             | EXCEPT is not supported",
        "SELECT name FROM products MINUS SELECT name FROM products              | MINUS is not supported",
    })
    void unsupportedConstructIsRejectedWithClearMessage(String sql, String expectedMessage) {
        QueryParseException ex = rejected(sql);
        assertTrue(ex.getMessage().contains(expectedMessage),
            "Expected message containing '" + expectedMessage + "' but got: " + ex.getMessage());
    }

    @Test
    void stringConcatenationInSelectIsRejected() {
        assertTrue(rejected("SELECT name || category FROM products").getMessage()
            .contains("Expression in SELECT list: name || category is not supported"));
    }

    @Test
    void hintTellsUserHowToProjectAllColumns() {
        assertTrue(rejected("SELECT p.* FROM products p").getMessage().contains("use SELECT *"));
    }

    @Test
    void setOperationHintNamesSupportedOperators() {
        assertTrue(rejected("SELECT name FROM products INTERSECT SELECT name FROM products")
            .getMessage().contains("only UNION and UNION ALL are supported"));
    }

    @Test
    void rejectedInsideCteBody() {
        QueryParseException ex = rejected(
            "WITH c AS (SELECT UPPER(name) FROM products) SELECT * FROM c");
        assertTrue(ex.getMessage().contains("UPPER(name)"));
    }

    @Test
    void rejectedInsideUnionBranch() {
        QueryParseException ex = rejected(
            "SELECT name FROM products UNION ALL SELECT UPPER(name) FROM products");
        assertTrue(ex.getMessage().contains("UPPER(name)"));
    }

    @Test
    void rejectedInJoinedQueryEvenWhenLaterJoinsAreValid() {
        QueryParseException ex = rejected("SELECT o.orderId FROM orders o "
            + "JOIN products p ON o.productId = p.id RIGHT JOIN products q ON o.productId = q.id");
        assertTrue(ex.getMessage().contains("RIGHT JOIN"));
    }

    @Test
    void executorSurfacesParseErrorInsteadOfReturningRows() {
        assertThrows(QueryParseException.class,
            () -> executor.execute("SELECT UPPER(category) FROM products"));
        assertThrows(QueryParseException.class,
            () -> executor.execute("SELECT o.orderId FROM orders o RIGHT JOIN products p ON o.productId = p.id"));
    }

    @Test
    void dryRunAlsoRejects() {
        assertThrows(QueryParseException.class,
            () -> executor.dryRunValidate("SELECT UPPER(name) FROM products"));
    }

    // -- forms that must remain accepted ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM products",
        "SELECT DISTINCT * FROM products",
        "SELECT TOP 1 * FROM products",
        "SELECT name, price AS cost, category c FROM products",
        "SELECT p.name, p.price FROM products p",
        "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o INNER JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o LEFT OUTER JOIN products p ON o.productId = p.id",
        "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)",
        "SELECT name, tag FROM products JOIN UNNEST(tags) AS t(tag)",
        "SELECT name, tag FROM products CROSS JOIN UNNEST(tags) AS t(tag)",
        "SELECT name FROM products ORDER BY price DESC LIMIT 1",
        "SELECT name FROM products UNION SELECT name FROM products",
        "SELECT name FROM products UNION ALL SELECT name FROM products",
    })
    void supportedFormsStillExecute(String sql) throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(sql));
        assertTrue(rows.isArray(), sql);
        assertFalse(rows.isEmpty(), sql);
    }

    @Test
    void leftOuterJoinKeepsUnmatchedRows() throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(
            "SELECT o.orderId, p.name FROM orders o LEFT OUTER JOIN products p ON o.productId = p.id"));
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).get("name").isNull());
    }

    @Test
    void crossJoinUnnestFlattensArray() throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(
            "SELECT name, tag FROM products CROSS JOIN UNNEST(tags) AS t(tag)"));
        assertEquals(3, rows.size());
    }
}
