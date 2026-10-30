package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the same queries through JsonSQL and SQLite and requires the same answer. Expected
 * results written by hand can repeat a misunderstanding, so SQLite acts as an independent
 * reference. The data is deliberately awkward: missing fields as well as nulls, duplicates,
 * empty strings, zeros, negative numbers, and % and _ inside strings.
 *
 * Differences that are intended aren't compared. Field names are case-sensitive here, and
 * SQLite's LIKE is switched to case-sensitive to match.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteComparisonTest {

    private static final String PRODUCTS = """
        [{"id": 1, "name": "Widget", "price": 10, "cat": "Tools", "qty": 5},
         {"id": 2, "name": "Gadget", "price": 20, "cat": "Tools", "qty": 0},
         {"id": 3, "name": "widget", "price": 20.5, "cat": "Toys", "qty": 3},
         {"id": 4, "name": "Doohickey", "price": null, "cat": "Toys", "qty": 7},
         {"id": 5, "name": "Thing", "cat": "Misc", "qty": 1},
         {"id": 6, "name": "Thing", "price": 0, "cat": null, "qty": 2},
         {"id": 7, "name": "", "price": -5, "cat": "Misc"},
         {"id": 8, "name": "50%_off", "price": 30, "cat": "", "qty": 4},
         {"id": 9, "name": "Widget", "price": 10, "cat": "Tools", "qty": 5},
         {"id": 10, "name": "Zed", "price": 99.99}]
        """;
    private static final String ORDERS = """
        [{"id": 100, "pid": 1, "n": 2, "st": "open"},
         {"id": 101, "pid": 1, "n": 1, "st": "done"},
         {"id": 102, "pid": 2, "n": 5, "st": "open"},
         {"id": 103, "pid": 4, "n": 1, "st": null},
         {"id": 104, "pid": 999, "n": 1, "st": "open"},
         {"id": 105, "pid": null, "n": 3, "st": "done"},
         {"id": 106, "n": 2, "st": "open"},
         {"id": 107, "pid": 9, "n": 4}]
        """;
    private static final String CODES = """
        [{"id": 1, "st": "open", "label": "Open"},
         {"id": 2, "st": "done", "label": "Done"},
         {"id": 3, "st": null, "label": "None"}]
        """;
    private static final String KINDS = """
        [{"cat": "Tools"}, {"cat": "Toys"}, {"cat": null}, {"cat": "Tools"}]
        """;

    private static final List<String> ATOMS = List.of(
        "price > 20", "price >= 20", "price < 20", "price <= 20", "price = 20", "price != 20", "price <> 20",
        "price > -1", "price = 0", "qty = 0", "qty > 0",
        "name = 'Widget'", "name != 'Widget'", "name = ''", "name <> ''", "name > 'M'", "name < 'M'",
        "name LIKE 'W%'", "name LIKE '%idget'", "name LIKE '_idget'", "name LIKE '%a%'", "name NOT LIKE 'W%'",
        "name LIKE ''", "name LIKE '%'",
        "cat IN ('Tools', 'Toys')", "cat NOT IN ('Tools', 'Toys')", "cat IN ('Tools', NULL)", "cat NOT IN ('Tools', NULL)",
        "price IN (10, 20)", "price NOT IN (10, 20)", "price IN (20.5)",
        "price IS NULL", "price IS NOT NULL", "cat IS NULL", "cat IS NOT NULL", "qty IS NULL",
        "cat = ''", "cat != 'Tools'",
        "price BETWEEN 10 AND 20", "price NOT BETWEEN 10 AND 20", "id BETWEEN 3 AND 3", "id BETWEEN 5 AND 1",
        "name BETWEEN 'A' AND 'M'", "qty BETWEEN 1 AND price", "15 BETWEEN qty AND price",
        "price BETWEEN NULL AND 20", "price NOT BETWEEN NULL AND 5", "price NOT BETWEEN 100 AND NULL",
        "20 < price", "'Widget' = name", "0 = qty", "price = qty", "id = qty", "price > qty", "name = cat",
        "price = 20.0", "price = 2e1", "price > -1.5", "price = '20'", "name = 5", "name > 5",
        "name = 'O''Brien'", "name LIKE '50!%!_off' ESCAPE '!'", "name LIKE 'Widge!t' ESCAPE '!'", "price = NULL", "cat LIKE NULL");

    private static final String JOIN_COLUMNS = "p.id AS pi, o.id AS oi, o.n AS n, p.name AS nm";

    private record Query(String sql, List<String> orderedBy) {
        @Override
        public String toString() {
            return sql;
        }
    }

    // Static, because instance fields aren't set yet when @BeforeAll runs
    @TempDir
    static File dataDir;

    private Connection sqlite;
    private QueryExecutor jsonSql;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void loadBothEngines() throws Exception {
        MappingManager mappings = new MappingManager(new File(dataDir, ".jsonsql-mappings.json"));
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = sqlite.createStatement()) {
            statement.execute("PRAGMA case_sensitive_like = ON");
            statement.execute("CREATE TABLE p (id INTEGER, name TEXT, price REAL, cat TEXT, qty INTEGER)");
            statement.execute("CREATE TABLE o (id INTEGER, pid INTEGER, n INTEGER, st TEXT)");
            statement.execute("CREATE TABLE c (id INTEGER, st TEXT, label TEXT)");
            statement.execute("CREATE TABLE k (cat TEXT)");
        }
        load(mappings, "p", PRODUCTS, "id", "name", "price", "cat", "qty");
        load(mappings, "o", ORDERS, "id", "pid", "n", "st");
        load(mappings, "c", CODES, "id", "st", "label");
        load(mappings, "k", KINDS, "cat");
        jsonSql = new QueryExecutor(mappings, dataDir);
    }

    private void load(MappingManager mappings, String table, String json, String... columns) throws Exception {
        Files.writeString(new File(dataDir, table + ".json").toPath(), json);
        mappings.addMapping(table, table + ".json:$[*]");

        String placeholders = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
        try (PreparedStatement insert = sqlite.prepareStatement("INSERT INTO " + table + " VALUES (" + placeholders + ")")) {
            for (JsonNode row : mapper.readTree(json)) {
                for (int i = 0; i < columns.length; i++) {
                    JsonNode value = row.get(columns[i]);
                    if (value == null || value.isNull()) {
                        insert.setObject(i + 1, null);
                    } else if (value.isNumber()) {
                        insert.setDouble(i + 1, value.asDouble());
                    } else {
                        insert.setString(i + 1, value.asText());
                    }
                }
                insert.executeUpdate();
            }
        }
    }

    @AfterAll
    void closeSqlite() throws SQLException {
        sqlite.close();
    }

    private static Query any(String sql) {
        return new Query(sql, List.of());
    }

    private static Query ordered(String sql, String... columns) {
        return new Query(sql, List.of(columns));
    }

    static Stream<Query> whereQueries() {
        List<Query> queries = new ArrayList<>();
        for (String atom : ATOMS) {
            queries.add(any("SELECT id FROM p WHERE " + atom));
            queries.add(any("SELECT id FROM p WHERE NOT (" + atom + ")"));
        }
        List<String> some = new ArrayList<>();
        for (int i = 0; i < ATOMS.size(); i += 3) {
            some.add(ATOMS.get(i));
        }
        for (String a : some) {
            for (String b : some) {
                if (!a.equals(b)) {
                    queries.add(any("SELECT id FROM p WHERE " + a + " AND " + b));
                    queries.add(any("SELECT id FROM p WHERE " + a + " OR " + b));
                }
            }
        }
        for (int i = 0; i + 2 < some.size(); i++) {
            String a = some.get(i);
            String b = some.get(i + 1);
            String c = some.get(i + 2);
            queries.add(any("SELECT id FROM p WHERE " + a + " AND (" + b + " OR " + c + ")"));
            queries.add(any("SELECT id FROM p WHERE (" + a + " OR " + b + ") AND " + c));
            queries.add(any("SELECT id FROM p WHERE NOT (" + a + " AND " + b + ")"));
            queries.add(any("SELECT id FROM p WHERE NOT (" + a + " OR " + b + ")"));
            queries.add(any("SELECT id FROM p WHERE NOT (NOT (" + a + "))"));
        }
        return queries.stream();
    }

    static Stream<Query> selectAndOrderQueries() {
        List<Query> queries = new ArrayList<>();
        for (String columns : List.of("*", "id, name", "name, id", "price AS cost", "id AS a, name AS b", "cat", "qty, price, cat", "\"id\"")) {
            queries.add(any("SELECT " + columns + " FROM p"));
        }
        queries.add(any("SELECT p.id, p.name FROM p"));
        queries.add(any("SELECT x.id FROM p x"));
        queries.add(any("SELECT x.id AS y FROM p AS x"));
        queries.add(any("select id from p where qty > 3"));
        for (String columns : List.of("cat", "name", "price", "cat, price", "cat, name", "name, price, qty", "*")) {
            queries.add(any("SELECT DISTINCT " + columns + " FROM p"));
        }
        queries.add(any("SELECT DISTINCT name FROM p WHERE cat = 'Tools'"));
        queries.add(any("SELECT DISTINCT cat FROM k"));
        queries.add(ordered("SELECT DISTINCT cat FROM p ORDER BY cat", "cat"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY price", "price"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY price ASC", "price"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY price DESC", "price"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY name", "name"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY name DESC", "name"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY cat", "cat"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY cat DESC", "cat"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY cat, price", "cat", "price"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY cat DESC, price ASC", "cat", "price"));
        queries.add(ordered("SELECT id, name, price, cat, qty FROM p ORDER BY qty DESC", "qty"));
        queries.add(ordered("SELECT id AS a, price AS b FROM p ORDER BY b", "b"));
        queries.add(ordered("SELECT id, price FROM p ORDER BY 2 DESC", "price"));
        queries.add(ordered("SELECT id AS a FROM p ORDER BY id DESC", "a"));
        for (int count : List.of(0, 1, 3, 100)) {
            queries.add(ordered("SELECT id FROM p ORDER BY id LIMIT " + count, "id"));
            queries.add(ordered("SELECT id FROM p ORDER BY id DESC LIMIT " + count, "id"));
            queries.add(ordered("SELECT id FROM o WHERE n > 1 ORDER BY id LIMIT " + count, "id"));
        }
        for (int offset : List.of(0, 2, 8, 100)) {
            queries.add(ordered("SELECT id FROM p ORDER BY id LIMIT 3 OFFSET " + offset, "id"));
            queries.add(ordered("SELECT id FROM p ORDER BY id DESC LIMIT 3 OFFSET " + offset, "id"));
        }
        queries.add(ordered("SELECT id FROM p ORDER BY id LIMIT 2, 3", "id"));
        queries.add(ordered("SELECT DISTINCT cat FROM p ORDER BY cat LIMIT 2 OFFSET 1", "cat"));
        return queries.stream();
    }

    static Stream<Query> joinQueries() {
        String j = JOIN_COLUMNS;
        return Stream.of(
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid"),
            any("SELECT " + j + " FROM p INNER JOIN o ON p.id = o.pid"),
            any("SELECT " + j + " FROM o JOIN p ON p.id = o.pid"),
            any("SELECT " + j + " FROM o JOIN p ON o.pid = p.id"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON o.pid = p.id"),
            any("SELECT " + j + " FROM o LEFT JOIN p ON p.id = o.pid"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid WHERE o.id IS NULL"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid WHERE o.n > 1"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid WHERE o.st = 'open'"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid WHERE p.price > 10 AND o.n >= 2"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid WHERE NOT (o.st = 'open')"),
            ordered("SELECT " + j + " FROM p JOIN o ON p.id = o.pid ORDER BY o.id", "oi"),
            ordered("SELECT " + j + " FROM p JOIN o ON p.id = o.pid ORDER BY o.id DESC LIMIT 2", "oi"),
            any("SELECT o.id AS oi, c.label AS l FROM o JOIN c ON o.st = c.st"),
            any("SELECT o.id AS oi, c.label AS l FROM o LEFT JOIN c ON o.st = c.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p JOIN o ON p.id = o.pid JOIN c ON o.st = c.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p LEFT JOIN o ON p.id = o.pid LEFT JOIN c ON o.st = c.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p JOIN o ON p.id = o.pid LEFT JOIN c ON c.st = o.st"),
            any("SELECT a.id AS x, b.id AS y FROM p a JOIN p b ON a.name = b.name"),
            any("SELECT a.id AS x, b.id AS y FROM p a JOIN p b ON a.cat = b.cat WHERE a.id < 4"),
            any("SELECT a.id AS x, b.id AS y FROM p a LEFT JOIN p b ON b.cat = a.cat"));
    }

    static Stream<Query> unionAndCteQueries() {
        return Stream.of(
            any("SELECT id FROM p UNION SELECT id FROM o"),
            any("SELECT id FROM p UNION ALL SELECT id FROM o"),
            any("SELECT cat FROM p UNION SELECT cat FROM k"),
            any("SELECT cat FROM p UNION ALL SELECT cat FROM k"),
            any("SELECT cat FROM p UNION ALL SELECT cat FROM p UNION SELECT cat FROM k"),
            any("SELECT cat FROM p UNION SELECT cat FROM k UNION ALL SELECT cat FROM k"),
            any("SELECT id, name FROM p UNION SELECT id, st FROM o"),
            any("SELECT id AS a FROM p UNION SELECT id FROM o"),
            any("SELECT id AS a FROM p UNION SELECT pid FROM o"),
            any("SELECT id AS a FROM p UNION ALL SELECT pid FROM o"),
            any("SELECT id FROM p WHERE qty > 1 UNION SELECT id FROM p WHERE price > 10"),
            ordered("SELECT id FROM p UNION SELECT id FROM o ORDER BY id", "id"),
            ordered("SELECT id FROM p UNION SELECT id FROM o ORDER BY id DESC LIMIT 3", "id"),
            ordered("SELECT cat FROM p UNION ALL SELECT cat FROM k ORDER BY cat LIMIT 4", "cat"),
            ordered("SELECT cat FROM p UNION SELECT cat FROM k ORDER BY cat", "cat"),
            ordered("SELECT id FROM p UNION SELECT id FROM o ORDER BY id LIMIT 3 OFFSET 2", "id"),
            ordered("SELECT cat FROM p UNION ALL SELECT cat FROM k ORDER BY cat LIMIT 4 OFFSET 2", "cat"),
            ordered("SELECT id AS a, name AS b FROM p UNION SELECT id, st FROM o ORDER BY a", "a"),
            ordered("SELECT id AS a FROM p UNION SELECT id FROM o ORDER BY id DESC LIMIT 3", "a"),
            ordered("SELECT id AS a FROM p UNION SELECT id FROM o ORDER BY 1 LIMIT 3", "a"),
            any("WITH x AS (SELECT id, cat FROM p WHERE qty > 1) SELECT * FROM x"),
            any("WITH x AS (SELECT id, cat FROM p WHERE qty > 1) SELECT id FROM x WHERE cat = 'Tools'"),
            ordered("WITH x AS (SELECT id AS a FROM p) SELECT a FROM x ORDER BY a DESC LIMIT 2", "a"),
            any("WITH x AS (SELECT id, name FROM p), y AS (SELECT id FROM x WHERE name = 'Widget') SELECT * FROM y"),
            any("WITH x AS (SELECT id, name FROM p WHERE id < 5) SELECT x.id AS a, o.id AS b FROM x JOIN o ON x.id = o.pid"),
            any("WITH x AS (SELECT id, name FROM p WHERE id < 5) SELECT o.id AS b, x.name AS n FROM o LEFT JOIN x ON x.id = o.pid"),
            any("WITH x AS (SELECT DISTINCT cat FROM p) SELECT cat FROM x"),
            any("WITH x AS (SELECT id FROM p ORDER BY id DESC LIMIT 3) SELECT id FROM x"),
            ordered("WITH x AS (SELECT id FROM p ORDER BY id LIMIT 3 OFFSET 2) SELECT id FROM x ORDER BY id", "id"),
            any("WITH X AS (SELECT id FROM p) SELECT id FROM x"),
            any("WITH p AS (SELECT id FROM o) SELECT id FROM p"));
    }

    @ParameterizedTest
    @MethodSource("whereQueries")
    void whereMatchesSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    @ParameterizedTest
    @MethodSource("selectAndOrderQueries")
    void selectAndOrderMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    @ParameterizedTest
    @MethodSource("joinQueries")
    void joinsMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    @ParameterizedTest
    @MethodSource("unionAndCteQueries")
    void unionsAndCtesMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    private void assertMatchesSqlite(Query query) throws Exception {
        List<String> columns = new ArrayList<>();
        List<List<String>> expected = runSqlite(query.sql(), columns);
        List<List<String>> actual = runJsonSql(query.sql(), columns);

        assertEquals(sorted(expected), sorted(actual), "rows differ");
        if (!query.orderedBy().isEmpty()) {
            assertEquals(project(expected, columns, query.orderedBy()), project(actual, columns, query.orderedBy()), "order differs");
        }
    }

    private List<List<String>> runSqlite(String sql, List<String> columnsOut) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = sqlite.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                columnsOut.add(metadata.getColumnLabel(i));
            }
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnsOut.size(); i++) {
                    Object value = result.getObject(i);
                    row.add(value instanceof Number number ? normalize(number.doubleValue()) : String.valueOf(value));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<List<String>> runJsonSql(String sql, List<String> columns) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode node : mapper.readTree(jsonSql.execute(sql))) {
            List<String> row = new ArrayList<>();
            for (String column : columns) {
                JsonNode value = node.get(column);
                if (value == null || value.isNull()) {
                    row.add("null");
                } else {
                    row.add(value.isNumber() ? normalize(value.asDouble()) : value.asText());
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static String normalize(double value) {
        return Double.toString(value);
    }

    private static List<String> sorted(List<List<String>> rows) {
        return rows.stream().map(Object::toString).sorted().toList();
    }

    private static List<List<String>> project(List<List<String>> rows, List<String> columns, List<String> wanted) {
        List<List<String>> projected = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : wanted) {
                values.add(row.get(columns.indexOf(column)));
            }
            projected.add(values);
        }
        return projected;
    }
}
