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
 * The SQLite columns have no declared type, like JSON fields. A typed column would make
 * SQLite convert values before comparing them, which JsonSQL never does.
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
    /** One column holding numbers and text together, which JSON allows and typed SQL columns don't. */
    private static final String MIXED = """
        [{"id": 1, "v": 10}, {"id": 2, "v": "10"}, {"id": 3, "v": 9}, {"id": 4, "v": "9"},
         {"id": 5, "v": "abc"}, {"id": 6, "v": 10.0}, {"id": 7, "v": null}, {"id": 8},
         {"id": 9, "v": ""}, {"id": 10, "v": 2.5}, {"id": 11, "v": "Abc"}, {"id": 12, "v": -1},
         {"id": 13, "v": "2.5x"}]
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

    /**
     * @param sqliteSql the same query for SQLite, which rejects an unprefixed name that two
     *                  tables share; JsonSQL reads it from the earliest table that has it
     */
    private record Query(String sql, List<String> orderedBy, String sqliteSql) {
        Query(String sql, List<String> orderedBy) {
            this(sql, orderedBy, sql);
        }

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
            statement.execute("CREATE TABLE p (id, name, price, cat, qty)");
            statement.execute("CREATE TABLE o (id, pid, n, st)");
            statement.execute("CREATE TABLE c (id, st, label)");
            statement.execute("CREATE TABLE k (cat)");
            statement.execute("CREATE TABLE m (id, v)");
        }
        load(mappings, "p", PRODUCTS, "id", "name", "price", "cat", "qty");
        load(mappings, "o", ORDERS, "id", "pid", "n", "st");
        load(mappings, "c", CODES, "id", "st", "label");
        load(mappings, "k", KINDS, "cat");
        load(mappings, "m", MIXED, "id", "v");
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

    /** A query with an unprefixed shared name, and the same query with that name prefixed for SQLite. */
    private static Query prefixedForSqlite(String sql, String sqliteSql, String... orderedBy) {
        return new Query(sql, List.of(orderedBy), sqliteSql);
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
            any("SELECT a.id AS x, b.id AS y FROM p a LEFT JOIN p b ON b.cat = a.cat"),
            // Conditions in ON beyond a single field = field
            any("SELECT " + j + " FROM o JOIN p ON p.id = o.pid AND o.st = 'open'"),
            any("SELECT " + j + " FROM o LEFT JOIN p ON p.id = o.pid AND o.st = 'open'"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid AND o.n > 1"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid AND p.price > 10"),
            any("SELECT o.id AS oi, c.label AS l FROM o JOIN c ON o.st = c.st AND o.pid = c.id"),
            any("SELECT o.id AS oi, c.label AS l FROM o LEFT JOIN c ON c.id = o.pid AND c.st = o.st"),
            any("SELECT " + j + " FROM p JOIN o ON o.n BETWEEN p.qty - 1 AND p.qty"),
            any("SELECT " + j + " FROM p JOIN o ON p.price > o.n * 5 AND p.price < o.n * 10"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON o.n > p.qty"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid OR o.n > 4"),
            any("SELECT " + j + " FROM p LEFT JOIN o ON p.id = o.pid OR o.st IS NULL"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid AND NOT (o.st = 'open')"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid AND o.st IN ('open', NULL)"),
            any("SELECT " + j + " FROM p JOIN o ON p.id = o.pid AND p.name LIKE 'W%'"),
            any("SELECT a.id AS x, b.id AS y FROM p a JOIN p b ON LOWER(a.name) = LOWER(b.name)"),
            any("SELECT a.id AS x, b.id AS y FROM p a LEFT JOIN p b ON a.id + 1 = b.id AND a.cat = b.cat"),
            any("SELECT a.id AS x, b.id AS y FROM p a JOIN p b ON a.cat = b.cat AND a.id < b.id"),
            any("SELECT a.id AS x, b.id AS y FROM p a JOIN p b ON CASE WHEN a.qty > 4 THEN a.id ELSE 0 END = b.id"),
            any("SELECT a.id AS x, b.id AS y FROM m a JOIN m b ON a.v = b.v AND a.id < b.id"),
            any("SELECT a.id AS x, b.id AS y FROM m a JOIN m b ON a.id + 1 = b.id AND a.v < b.v"),
            any("SELECT c.id AS ci, o.id AS oi FROM c JOIN o ON o.n = o.pid"),
            any("SELECT c.id AS ci, o.id AS oi FROM c LEFT JOIN o ON o.n = o.pid"),
            any("SELECT p.id AS pi, o.id AS oi FROM p JOIN o ON p.id = p.qty AND o.n = 1"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p JOIN o ON p.id = o.pid JOIN c ON o.n = p.qty AND c.st = o.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p JOIN o ON p.id = o.pid LEFT JOIN c ON o.st = c.st AND p.qty > 2"),
            // RIGHT, FULL and CROSS joins, and commas
            any("SELECT " + j + " FROM o RIGHT JOIN p ON p.id = o.pid"),
            any("SELECT " + j + " FROM p RIGHT JOIN o ON p.id = o.pid"),
            any("SELECT " + j + " FROM p RIGHT OUTER JOIN o ON o.pid = p.id AND o.st = 'open'"),
            any("SELECT " + j + " FROM o RIGHT JOIN p ON p.id = o.pid AND p.price > 10"),
            any("SELECT " + j + " FROM o RIGHT JOIN p ON o.n > p.qty"),
            any("SELECT " + j + " FROM o RIGHT JOIN p ON p.id = o.pid WHERE o.id IS NULL"),
            any("SELECT " + j + " FROM o RIGHT JOIN p ON p.id = o.pid WHERE p.cat = 'Tools' OR o.n > 1"),
            any("SELECT " + j + " FROM p FULL JOIN o ON p.id = o.pid"),
            any("SELECT " + j + " FROM o FULL OUTER JOIN p ON p.id = o.pid AND o.st = 'open'"),
            any("SELECT " + j + " FROM p FULL JOIN o ON p.id = o.pid OR o.n > 4"),
            any("SELECT " + j + " FROM p FULL JOIN o ON p.id = o.pid WHERE p.id IS NULL OR o.id IS NULL"),
            any("SELECT o.id AS oi, c.label AS l FROM o FULL JOIN c ON o.st = c.st"),
            any("SELECT o.id AS oi, c.label AS l FROM o RIGHT JOIN c ON o.st = c.st AND o.pid = c.id"),
            any("SELECT a.id AS x, b.id AS y FROM m a FULL JOIN m b ON a.v = b.v AND a.id < b.id"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p JOIN o ON p.id = o.pid RIGHT JOIN c ON o.st = c.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p RIGHT JOIN o ON p.id = o.pid LEFT JOIN c ON o.st = c.st"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p FULL JOIN o ON p.id = o.pid FULL JOIN c ON c.id = p.id"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p LEFT JOIN o ON p.id = o.pid RIGHT JOIN c ON c.st = o.st"),
            any("SELECT o.id AS oi, c.label AS l FROM o CROSS JOIN c"),
            any("SELECT o.id AS oi, c.label AS l FROM o, c"),
            any("SELECT o.id AS oi, c.label AS l FROM o, c WHERE o.st = c.st AND o.n > 1"),
            any("SELECT p.id AS pi, o.id AS oi, c.label AS l FROM p, o, c WHERE p.id = o.pid AND c.st = o.st"),
            any("SELECT o.id AS oi, c.label AS l FROM o CROSS JOIN c LEFT JOIN p ON p.id = o.pid AND c.id = 1"),
            any("SELECT COUNT(*) AS n, COUNT(o.id) AS a, COUNT(p.id) AS b FROM p FULL JOIN o ON p.id = o.pid"),
            any("SELECT p.cat AS cat, COUNT(o.id) AS n FROM o RIGHT JOIN p ON p.id = o.pid GROUP BY p.cat"),
            // An unprefixed name is read from the earliest table that has it, also on unmatched rows
            prefixedForSqlite("SELECT p.id AS pi, id AS x FROM o RIGHT JOIN p ON p.id = o.pid",
                "SELECT p.id AS pi, o.id AS x FROM o RIGHT JOIN p ON p.id = o.pid"),
            prefixedForSqlite("SELECT p.id AS pi FROM o RIGHT JOIN p ON p.id = o.pid WHERE id IS NULL",
                "SELECT p.id AS pi FROM o RIGHT JOIN p ON p.id = o.pid WHERE o.id IS NULL"),
            prefixedForSqlite("SELECT p.id AS pi, o.id AS oi FROM o FULL JOIN p ON p.id = o.pid WHERE id > 101",
                "SELECT p.id AS pi, o.id AS oi FROM o FULL JOIN p ON p.id = o.pid WHERE o.id > 101"),
            prefixedForSqlite("SELECT id AS x, p.id AS pi FROM o FULL JOIN p ON p.id = o.pid ORDER BY id, p.id",
                "SELECT o.id AS x, p.id AS pi FROM o FULL JOIN p ON p.id = o.pid ORDER BY o.id, p.id", "x", "pi"),
            prefixedForSqlite("SELECT c.label AS l, st AS s FROM o RIGHT JOIN c ON o.st = c.st AND o.n > 1",
                "SELECT c.label AS l, o.st AS s FROM o RIGHT JOIN c ON o.st = c.st AND o.n > 1"),
            prefixedForSqlite("SELECT id AS x, o.id AS oi FROM p FULL JOIN o ON p.id = o.pid",
                "SELECT p.id AS x, o.id AS oi FROM p FULL JOIN o ON p.id = o.pid"),
            prefixedForSqlite("SELECT id AS x, c.label AS l FROM o RIGHT JOIN p ON p.id = o.pid JOIN c ON c.id = p.id",
                "SELECT o.id AS x, c.label AS l FROM o RIGHT JOIN p ON p.id = o.pid JOIN c ON c.id = p.id"),
            prefixedForSqlite("SELECT id AS x, c.label AS l FROM o FULL JOIN p ON p.id = o.pid LEFT JOIN c ON c.st = o.st",
                "SELECT o.id AS x, c.label AS l FROM o FULL JOIN p ON p.id = o.pid LEFT JOIN c ON c.st = o.st"),
            // An unprefixed name on one side and an earlier table's name on the other
            any("SELECT " + j + " FROM o JOIN p ON qty = o.n"),
            any("SELECT " + j + " FROM o JOIN p ON o.n = qty"),
            any("SELECT " + j + " FROM o LEFT JOIN p ON qty = o.n AND price > 0"),
            any("SELECT " + j + " FROM o RIGHT JOIN p ON qty = o.n"));
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
            any("WITH p AS (SELECT id FROM o) SELECT id FROM p"),
            // INTERSECT and EXCEPT; chains only where SQLite and PostgreSQL agree on the order
            any("SELECT id FROM p INTERSECT SELECT pid FROM o"),
            any("SELECT id FROM p EXCEPT SELECT pid FROM o"),
            any("SELECT pid FROM o EXCEPT SELECT id FROM p"),
            any("SELECT cat FROM p INTERSECT SELECT cat FROM k"),
            any("SELECT cat FROM p EXCEPT SELECT cat FROM k"),
            any("SELECT cat FROM k EXCEPT SELECT cat FROM p"),
            any("SELECT name FROM p INTERSECT SELECT name FROM p WHERE id > 4"),
            any("SELECT id, cat FROM p INTERSECT SELECT id, cat FROM p WHERE qty > 2"),
            any("SELECT name, cat FROM p EXCEPT SELECT name, cat FROM p WHERE price > 10"),
            any("SELECT id AS a FROM p INTERSECT SELECT pid FROM o"),
            any("SELECT v FROM m INTERSECT SELECT v FROM m WHERE id > 5"),
            any("SELECT v FROM m EXCEPT SELECT v FROM m WHERE id < 6"),
            any("SELECT st FROM o EXCEPT SELECT st FROM c"),
            any("SELECT st FROM o INTERSECT SELECT st FROM c"),
            any("SELECT id FROM p INTERSECT SELECT pid FROM o INTERSECT SELECT id FROM c"),
            any("SELECT id FROM p EXCEPT SELECT pid FROM o EXCEPT SELECT id FROM c"),
            any("SELECT id FROM p INTERSECT SELECT pid FROM o UNION SELECT id FROM c"),
            any("SELECT id FROM p INTERSECT SELECT pid FROM o UNION ALL SELECT id FROM c"),
            any("SELECT id FROM p EXCEPT SELECT pid FROM o UNION SELECT id FROM c"),
            any("SELECT id FROM p EXCEPT SELECT pid FROM o UNION ALL SELECT id FROM c"),
            any("SELECT id FROM p UNION SELECT pid FROM o EXCEPT SELECT id FROM c"),
            any("SELECT id FROM p UNION ALL SELECT pid FROM o EXCEPT SELECT id FROM c"),
            any("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat EXCEPT SELECT cat, 2 FROM k"),
            any("SELECT p.id AS i FROM p JOIN o ON p.id = o.pid INTERSECT SELECT id FROM p WHERE qty > 1"),
            any("WITH x AS (SELECT id FROM p WHERE qty > 2) SELECT id FROM p EXCEPT SELECT id FROM x"),
            ordered("SELECT id FROM p EXCEPT SELECT pid FROM o ORDER BY id DESC", "id"),
            ordered("SELECT id FROM p EXCEPT SELECT pid FROM o ORDER BY id DESC LIMIT 3", "id"),
            ordered("SELECT cat FROM p INTERSECT SELECT cat FROM k ORDER BY cat", "cat"),
            ordered("SELECT id AS a FROM p INTERSECT SELECT pid FROM o ORDER BY 1 LIMIT 2 OFFSET 1", "a"),
            ordered("SELECT id FROM p EXCEPT SELECT pid FROM o UNION SELECT id FROM c ORDER BY id LIMIT 4", "id"));
    }

    static Stream<Query> aggregateQueries() {
        List<Query> queries = new ArrayList<>();
        List<String> functions = List.of("COUNT(*)", "COUNT(price)", "COUNT(cat)", "COUNT(DISTINCT cat)", "COUNT(DISTINCT name)",
            "COUNT(1)", "count(*)", "SUM(price)", "SUM(qty)", "SUM(id)", "SUM(DISTINCT price)", "AVG(price)", "AVG(qty)",
            "AVG(DISTINCT qty)", "MIN(price)", "MAX(price)", "MIN(name)", "MAX(name)", "MIN(cat)", "MAX(cat)",
            "SUM(DISTINCT qty)", "AVG(DISTINCT price)");
        for (String function : functions) {
            queries.add(any("SELECT " + function + " FROM p"));
            queries.add(any("SELECT " + function + " AS v FROM p WHERE id > 99"));
            queries.add(any("SELECT cat, " + function + " AS v FROM p GROUP BY cat"));
            queries.add(any("SELECT name, price, " + function + " AS v FROM p GROUP BY name, price"));
            queries.add(any("SELECT qty, " + function + " AS v FROM p WHERE price > 0 GROUP BY qty"));
        }
        queries.add(any("SELECT cat FROM p GROUP BY cat"));
        queries.add(any("SELECT cat FROM k GROUP BY cat"));
        queries.add(any("SELECT cat, name FROM p GROUP BY cat, name"));
        queries.add(any("SELECT cat AS c, COUNT(*) AS n FROM p GROUP BY c"));
        queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY 1"));
        queries.add(any("SELECT cat, COUNT(*) FROM p WHERE id > 99 GROUP BY cat"));
        queries.add(any("SELECT DISTINCT cat FROM p GROUP BY cat, name"));
        queries.add(any("SELECT COUNT(*) AS a, SUM(qty) AS b, AVG(qty) AS c, MIN(qty) AS d, MAX(qty) AS e FROM p"));

        for (String condition : List.of("COUNT(*) > 1", "COUNT(*) = 1", "COUNT(*) != 2", "SUM(qty) > 5", "SUM(price) IS NULL", "SUM(price) IS NOT NULL",
                "NOT (SUM(price) > 10)", "AVG(price) BETWEEN 5 AND 25", "MAX(price) IN (20, 30)", "MIN(name) LIKE 'D%'", "COUNT(*) > 1 AND SUM(qty) > 5",
                "COUNT(*) > 2 OR SUM(qty) = 1", "1 < COUNT(*)", "COUNT(price) < COUNT(*)", "cat = 'Tools'", "cat IS NULL", "n > 1", "cat != 'Toys' AND n > 1", "count(*) > 1")) {
            queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat HAVING " + condition));
        }
        queries.add(any("SELECT COUNT(*) AS n FROM p HAVING COUNT(*) > 1"));
        queries.add(any("SELECT COUNT(*) AS n FROM p HAVING COUNT(*) > 100"));

        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY n DESC, cat", "n", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY cat", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY cat DESC", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY 2 DESC, 1", "n", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY COUNT(*) DESC, cat", "n", "cat"));
        queries.add(ordered("SELECT cat, SUM(qty) AS s FROM p GROUP BY cat ORDER BY SUM(qty)", "s"));
        queries.add(ordered("SELECT cat FROM p GROUP BY cat ORDER BY MAX(price) DESC, cat", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY n DESC, cat LIMIT 2", "n", "cat"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY n DESC, cat LIMIT 2 OFFSET 1", "n", "cat"));

        queries.add(any("SELECT p.cat, COUNT(o.id) AS n FROM p JOIN o ON p.id = o.pid GROUP BY p.cat"));
        queries.add(any("SELECT p.cat AS c, SUM(o.n) AS total FROM p LEFT JOIN o ON p.id = o.pid GROUP BY p.cat"));
        queries.add(any("SELECT p.name, COUNT(o.id) AS orders, SUM(o.n) AS units FROM p LEFT JOIN o ON o.pid = p.id GROUP BY p.name"));
        queries.add(any("SELECT o.st, COUNT(*) AS n FROM o GROUP BY o.st HAVING COUNT(*) > 1"));
        queries.add(any("SELECT COUNT(*) AS n, SUM(o.n) AS total FROM p JOIN o ON p.id = o.pid"));
        queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY p.cat"));

        queries.add(any("WITH x AS (SELECT cat, COUNT(*) AS n FROM p GROUP BY cat) SELECT cat FROM x WHERE n > 1"));
        queries.add(any("WITH x AS (SELECT cat, SUM(qty) AS s FROM p GROUP BY cat) SELECT x.cat, x.s, k.cat AS other FROM x JOIN k ON x.cat = k.cat"));
        queries.add(any("WITH x AS (SELECT id, cat FROM p WHERE qty > 1) SELECT cat, COUNT(*) AS n FROM x GROUP BY cat"));
        queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat UNION SELECT cat, COUNT(*) FROM k GROUP BY cat"));
        queries.add(any("SELECT COUNT(*) AS n FROM p UNION ALL SELECT COUNT(*) FROM o"));
        return queries.stream();
    }

    static Stream<Query> mixedTypeQueries() {
        List<Query> queries = new ArrayList<>();
        for (String atom : List.of("v = 10", "v = '10'", "v != 10", "v <> '10'", "v > 5", "v >= 10", "v < 5", "v > '5'", "v < 'a'",
                "v >= ''", "v = ''", "v IN (10, '9')", "v NOT IN (10, '9')", "v IN ('10', 9, 'abc')", "v BETWEEN 9 AND 10",
                "v BETWEEN 9 AND 'A'", "v BETWEEN '0' AND 'a'", "v NOT BETWEEN 0 AND 100", "10 = v", "'abc' > v", "v = 10.0",
                "v = 2.5", "v > 2.5", "v = v", "v = id", "v > id")) {
            queries.add(any("SELECT id FROM m WHERE " + atom));
            queries.add(any("SELECT id FROM m WHERE NOT (" + atom + ")"));
        }
        queries.add(ordered("SELECT id, v FROM m ORDER BY v, id", "v", "id"));
        queries.add(ordered("SELECT id, v FROM m ORDER BY v DESC, id", "v", "id"));
        queries.add(ordered("SELECT id, v FROM m WHERE v > 0 ORDER BY v, id DESC", "v", "id"));
        for (String function : List.of("MIN(v)", "MAX(v)", "COUNT(v)", "COUNT(DISTINCT v)")) {
            queries.add(any("SELECT " + function + " AS r FROM m"));
            queries.add(any("SELECT " + function + " AS r FROM m WHERE id > 3"));
        }
        queries.add(any("SELECT v, COUNT(*) AS n FROM m GROUP BY v"));
        queries.add(any("SELECT v, MIN(id) AS a, MAX(id) AS b FROM m GROUP BY v HAVING COUNT(*) > 1"));
        queries.add(any("SELECT DISTINCT v FROM m"));
        queries.add(any("SELECT a.id AS x, b.id AS y FROM m a JOIN m b ON a.v = b.v"));
        queries.add(any("SELECT a.id AS x, b.id AS y FROM m a LEFT JOIN m b ON b.v = a.v"));
        queries.add(any("SELECT m.id AS x, p.id AS y FROM m JOIN p ON m.v = p.price"));
        queries.add(any("SELECT v FROM m UNION SELECT v FROM m WHERE id > 5"));
        queries.add(ordered("SELECT v FROM m UNION SELECT id FROM m ORDER BY v", "v"));
        return queries.stream();
    }

    @ParameterizedTest
    @MethodSource("mixedTypeQueries")
    void mixedTypesMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    /**
     * Calculated columns where the two engines are meant to agree. Left out on purpose:
     * arithmetic on text and CAST of text with no number (SQLite gives 0, JsonSQL NULL),
     * numbers joined with || (every number here reaches SQLite as a REAL, so 10 would join
     * as 10.0), and conditions as values (SQLite gives 1 and 0, JsonSQL true and false).
     */
    static Stream<Query> expressionQueries() {
        List<Query> queries = new ArrayList<>();
        for (String expression : List.of(
                "price + 1", "price - qty", "price * qty", "price / 4", "qty / 3", "price / qty", "qty * 2", "-price",
                "(price + qty) * 2", "price * 1.5 + qty / 2", "price - qty - id", "id % 3", "price * 0", "0 - price",
                "price + NULL", "id * id - 1", "price / (qty - 5)", "price * 1.1",
                "CASE WHEN price > 15 THEN 'high' WHEN price IS NULL THEN 'none' ELSE 'low' END",
                "CASE WHEN qty = 0 THEN 'out' END",
                "CASE cat WHEN NULL THEN 'n' ELSE 'other' END",
                "CASE cat WHEN 'Tools' THEN 1 WHEN 'Toys' THEN 2 ELSE 0 END",
                "CASE name WHEN 'Widget' THEN price * 2 ELSE price END",
                "CASE WHEN cat IS NULL THEN 'none' ELSE cat END",
                "CASE WHEN price BETWEEN 10 AND 20 THEN 1 WHEN name LIKE 'Z%' THEN 2 END",
                "CASE WHEN qty > 3 AND price > 10 THEN 'a' WHEN NOT (qty > 3) THEN 'b' ELSE 'c' END",
                "CASE WHEN cat IN ('Tools', 'Misc') THEN qty ELSE 0 - qty END",
                "CASE WHEN price * 2 > 30 THEN 'big' ELSE 'small' END",
                "name || '-' || cat", "cat || ''", "'[' || name || ']'",
                "CAST(price AS INTEGER)", "CAST(price AS REAL)", "CAST(qty / 2 AS INTEGER)", "CAST(0 - price AS INTEGER)",
                "CAST(name AS TEXT)", "CAST(cat AS TEXT) || '!'", "CAST(NULL AS INTEGER)",
                "'x'", "5", "2.5", "NULL")) {
            queries.add(any("SELECT id, " + expression + " AS v FROM p"));
        }
        queries.add(ordered("SELECT id, price * qty AS v FROM p ORDER BY v, id", "v", "id"));
        queries.add(ordered("SELECT id, price * qty AS v FROM p ORDER BY v DESC, id", "v", "id"));
        queries.add(ordered("SELECT id, price / 4 AS v FROM p ORDER BY 2, 1", "v", "id"));
        queries.add(ordered("SELECT CASE WHEN qty > 3 THEN 'many' ELSE 'few' END AS band, id FROM p ORDER BY band, id", "band", "id"));
        queries.add(ordered("SELECT id, price * 2 AS v FROM p ORDER BY v LIMIT 3 OFFSET 2", "v"));
        queries.add(any("SELECT DISTINCT CASE WHEN qty > 3 THEN 'many' ELSE 'few' END AS band FROM p"));
        queries.add(any("SELECT DISTINCT price * 2 AS v FROM p"));
        queries.add(any("SELECT id, price * 2 AS v FROM p WHERE price > 10"));
        queries.add(any("SELECT o.id AS oi, o.n * p.price AS v, p.name || ':' || o.st AS label FROM o JOIN p ON o.pid = p.id"));
        queries.add(any("SELECT o.id AS oi, o.n * p.price AS v FROM o LEFT JOIN p ON o.pid = p.id"));
        queries.add(any("SELECT price * 2 AS v FROM p UNION SELECT n * 10 FROM o"));
        queries.add(any("SELECT price * 2 AS v FROM p UNION ALL SELECT price + price FROM p"));
        queries.add(any("WITH x AS (SELECT id, price * qty AS total FROM p) SELECT id, total / 2 AS half FROM x WHERE total > 20"));
        queries.add(any("WITH x AS (SELECT cat, price * 2 AS d FROM p) SELECT cat, SUM(d) AS s, AVG(d) AS a FROM x GROUP BY cat"));

        for (String condition : List.of(
                "price * qty > 50", "price + qty = 15", "price - qty < 10", "price / 4 >= 5", "qty % 2 = 0", "-price > 0",
                "price * 2 BETWEEN 20 AND 45", "qty BETWEEN price / 10 AND price", "qty * 2 IN (0, 10, 14)", "qty * 2 NOT IN (0, 10)",
                "price + qty IS NULL", "price + qty IS NOT NULL", "price / qty > 3", "price / qty IS NULL",
                "price * qty = qty * price", "id * 2 > price", "20 < price * 2",
                "name || cat = 'WidgetTools'", "name || '!' LIKE 'W%!'", "cat || name IN ('ToolsWidget', 'ToysDoohickey')",
                "name || cat IS NULL", "cat || name NOT LIKE 'Tools%'",
                "CASE WHEN qty > 3 THEN price ELSE 0 END > 5", "CASE cat WHEN 'Tools' THEN 1 ELSE 0 END = 1",
                "price > CASE WHEN qty > 3 THEN 15 ELSE 100 END",
                "CAST(price AS INTEGER) = 20", "CAST(price AS INTEGER) IN (10, 99)", "CAST(price AS INTEGER) IS NULL",
                "price * 2 > 30 OR qty + 1 = 1", "cat = 'Tools' AND price * qty >= 50")) {
            queries.add(any("SELECT id FROM p WHERE " + condition));
            queries.add(any("SELECT id FROM p WHERE NOT (" + condition + ")"));
        }
        for (String orderBy : List.of(
                "price * qty, id", "price * qty DESC, id", "price * -1, id", "qty % 2 DESC, id DESC", "name || cat, id",
                "CASE WHEN cat = 'Tools' THEN 0 ELSE 1 END, id", "CASE cat WHEN 'Toys' THEN 1 WHEN 'Tools' THEN 2 END, id DESC",
                "CAST(price AS INTEGER) DESC, id", "price / qty, id", "cat, price * qty DESC, id", "price > 15, id")) {
            queries.add(ordered("SELECT id FROM p ORDER BY " + orderBy, "id"));
        }
        queries.add(ordered("SELECT id, price * 2 AS d FROM p ORDER BY d + qty, id", "id"));
        queries.add(ordered("SELECT id, price AS cost FROM p ORDER BY cost * -1, id", "id"));
        queries.add(ordered("SELECT id FROM p WHERE price * qty > 10 ORDER BY price / qty, id LIMIT 3", "id"));
        queries.add(ordered("SELECT o.id AS oi FROM o JOIN p ON o.pid = p.id WHERE o.n * p.price > 15 ORDER BY o.n * p.price DESC, o.id", "oi"));
        return queries.stream();
    }

    @ParameterizedTest
    @MethodSource("expressionQueries")
    void expressionsMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    /**
     * Calculations with grouping. Left out on purpose, as well as what expressionQueries
     * leaves out: whole-number division such as COUNT(*) / 2, which SQLite rounds down.
     */
    static Stream<Query> groupedExpressionQueries() {
        List<Query> queries = new ArrayList<>();
        for (String value : List.of(
                "SUM(price * qty)", "AVG(price * qty)", "MIN(price - qty)", "MAX(price + qty)", "COUNT(price * qty)",
                "SUM(CASE WHEN qty > 3 THEN 1 ELSE 0 END)", "COUNT(CASE WHEN cat = 'Tools' THEN 1 END)",
                "SUM(DISTINCT qty * 2)", "COUNT(DISTINCT qty % 2)", "MAX(CAST(price AS INTEGER))", "SUM(1)", "MIN(name || '!')",
                "SUM(price) * 2", "SUM(price) / COUNT(*)", "MAX(price) - MIN(price)", "COUNT(*) * 2", "COUNT(*) / 2.0",
                "SUM(price * qty) / SUM(qty)", "CASE WHEN COUNT(*) > 1 THEN 'many' ELSE 'one' END", "CAST(AVG(price) AS INTEGER)",
                "SUM(qty) + COUNT(*) - 1", "-SUM(qty)", "COUNT(*) + COUNT(price)", "AVG(price) * AVG(qty)")) {
            queries.add(any("SELECT " + value + " AS v FROM p"));
            queries.add(any("SELECT " + value + " AS v FROM p WHERE id > 99"));
            queries.add(any("SELECT cat, " + value + " AS v FROM p GROUP BY cat"));
            queries.add(any("SELECT name, qty, " + value + " AS v FROM p WHERE price > 0 GROUP BY name, qty"));
        }
        queries.add(any("SELECT cat || '-' AS c, COUNT(*) AS n FROM p GROUP BY cat"));
        queries.add(any("SELECT qty % 2 AS k, COUNT(*) AS n FROM p GROUP BY qty % 2"));
        queries.add(any("SELECT COUNT(*) AS n, SUM(price) AS s FROM p GROUP BY qty > 3"));
        String band = "CASE WHEN price > 15 THEN 'high' ELSE 'low' END";
        queries.add(any("SELECT " + band + " AS band, COUNT(*) AS n, AVG(price) AS a FROM p GROUP BY " + band));
        queries.add(any("SELECT " + band + " AS band, COUNT(*) AS n FROM p GROUP BY band"));
        queries.add(any("SELECT " + band + " AS band, COUNT(*) AS n FROM p GROUP BY 1"));
        queries.add(any("SELECT " + band + " || '!' AS band, COUNT(*) AS n FROM p GROUP BY " + band));
        queries.add(any("SELECT cat, qty % 2 AS k, COUNT(*) AS n FROM p GROUP BY cat, qty % 2"));
        queries.add(any("SELECT price * 2 AS d, COUNT(*) AS n FROM p GROUP BY price * 2"));
        queries.add(any("SELECT CAST(price AS INTEGER) AS whole, COUNT(*) AS n FROM p GROUP BY CAST(price AS INTEGER)"));
        queries.add(any("SELECT name || cat AS k, COUNT(*) AS n FROM p GROUP BY name || cat"));
        queries.add(any("SELECT COUNT(*) AS n FROM p GROUP BY qty % 2 HAVING qty % 2 = 1"));
        queries.add(ordered("SELECT qty % 2 AS k, COUNT(*) AS n FROM p GROUP BY qty % 2 ORDER BY qty % 2 DESC", "k"));

        for (String condition : List.of(
                "SUM(price) * 2 > 50", "SUM(qty) / COUNT(*) >= 3", "COUNT(*) + 1 > 2", "MAX(price) - MIN(price) BETWEEN 5 AND 15",
                "SUM(price * qty) > 60", "cat || 'x' = 'Toolsx'", "n * 2 > 2", "CASE WHEN COUNT(*) > 1 THEN 1 ELSE 0 END = 1",
                "SUM(qty) + 0 IN (10, 3)", "SUM(price) + SUM(qty) IS NULL", "NOT (COUNT(*) * 2 > 2)",
                "COUNT(*) * 2 > 2 OR cat IS NULL", "AVG(price * 2) > 30", "cat || '' LIKE 'T%'")) {
            queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat HAVING " + condition));
        }
        queries.add(any("SELECT COUNT(*) AS n FROM p HAVING COUNT(*) * 2 > 5"));
        queries.add(any("SELECT COUNT(*) AS n FROM p HAVING SUM(price * qty) < 0"));

        for (String orderBy : List.of(
                "COUNT(*) * -1, cat", "SUM(qty) * 2 DESC, cat", "n * -1, cat", "MAX(price) - MIN(price), cat",
                "CASE WHEN cat IS NULL THEN 1 ELSE 0 END, cat", "SUM(price * qty) DESC, cat", "COUNT(*) + SUM(qty), cat")) {
            queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY " + orderBy, "cat"));
        }
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY SUM(qty) * 2 DESC, cat LIMIT 2 OFFSET 1", "cat"));

        queries.add(any("SELECT p.cat, SUM(o.n * p.price) AS v, COUNT(*) * 2 AS c FROM p JOIN o ON p.id = o.pid GROUP BY p.cat"));
        queries.add(any("SELECT p.name, SUM(o.n) * 10 AS units FROM p LEFT JOIN o ON o.pid = p.id GROUP BY p.name HAVING COUNT(o.id) + 1 > 1"));
        queries.add(any("WITH x AS (SELECT cat, SUM(price * qty) AS v FROM p GROUP BY cat) SELECT cat, v / 2 AS half FROM x WHERE v > 10"));
        queries.add(any("SELECT COUNT(*) * 2 AS v FROM p UNION ALL SELECT SUM(n) + 1 FROM o"));
        return queries.stream();
    }

    @ParameterizedTest
    @MethodSource("groupedExpressionQueries")
    void groupedExpressionsMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    /**
     * Functions where the two engines are meant to agree. Left out on purpose: a number given
     * to a text function (every number here reaches SQLite as a REAL, so 10 would be '10.0'),
     * text given to ROUND or ABS (SQLite reads it as a number, JsonSQL gives NULL), and
     * letters outside ASCII (SQLite's UPPER and LOWER leave them alone).
     */
    static Stream<Query> functionQueries() {
        List<Query> queries = new ArrayList<>();
        for (String value : List.of(
                "UPPER(name)", "LOWER(name)", "LENGTH(name)", "LENGTH(cat)", "UPPER(cat)",
                "SUBSTR(name, 1, id)", "SUBSTR(name, id - 5, 3)", "SUBSTR(name, id - 5, 3 - id)", "SUBSTR(cat, 2, 2)",
                "SUBSTR(name, NULL)", "SUBSTR(name, 1, NULL)", "SUBSTR(name, 2.9, 1.9)", "SUBSTR(name, -100, 102)",
                "TRIM(name)", "TRIM('  x  ')", "LTRIM('  x  ')", "RTRIM('  x  ')", "TRIM(name, 'Wt')", "LTRIM(name, 'WG')",
                "RTRIM(name, 'te')", "TRIM(cat, 'Ts')", "TRIM(name, '')", "TRIM(name, NULL)", "TRIM('xxx', 'x')",
                "REPLACE(name, 'e', 'E')", "REPLACE(name, 'dg', '')", "REPLACE(name, '', 'x')", "REPLACE(name, 'W', NULL)",
                "REPLACE(cat, 'o', '00')", "REPLACE(name, name, 'all')",
                "ROUND(price)", "ROUND(price, 1)", "ROUND(price, 2)", "ROUND(price / 3, 2)", "ROUND(price * qty / 7, 3)",
                "ROUND(price, -1)", "ROUND(qty)", "ROUND(price, NULL)", "ROUND(0 - price / 4, 1)", "ROUND(price / 4, 1)",
                "ROUND(2.5)", "ROUND(-2.5)", "ROUND(price, 1.9)", "ROUND(price, 40)",
                "ABS(price)", "ABS(qty - 3)", "ABS(0 - price)", "ABS(NULL)",
                "COALESCE(cat, 'none')", "COALESCE(price, qty, 0)", "COALESCE(NULL, NULL, name)", "IFNULL(cat, name)",
                "IFNULL(price, 0)", "NULLIF(qty, 0)", "NULLIF(cat, 'Tools')", "NULLIF(name, cat)", "NULLIF(price, qty * 2)",
                "NULLIF(qty, NULL)", "NULLIF(NULL, qty)",
                "UPPER(SUBSTR(name, 1, 1)) || LOWER(SUBSTR(name, 2))", "LENGTH(TRIM(name)) + LENGTH(COALESCE(cat, ''))",
                "ROUND(ABS(price - 15) / 3, 2)", "COALESCE(UPPER(cat), 'NONE') || '-' || LOWER(name)",
                "CASE WHEN LENGTH(name) > 5 THEN SUBSTR(name, 1, 5) ELSE name END", "CAST(ROUND(price) AS INTEGER)",
                "LENGTH(REPLACE(name, 'e', ''))")) {
            queries.add(any("SELECT id, " + value + " AS v FROM p"));
        }
        // SUBSTR has many special cases around zero, negative starts and negative lengths
        for (int start = -8; start <= 8; start++) {
            queries.add(any("SELECT id, SUBSTR(name, " + start + ") AS v FROM p"));
            for (int length = -4; length <= 4; length++) {
                queries.add(any("SELECT id, SUBSTR(name, " + start + ", " + length + ") AS v FROM p"));
            }
        }
        for (String condition : List.of(
                "UPPER(name) = 'WIDGET'", "LOWER(name) LIKE 'w%'", "LENGTH(name) > 5", "LENGTH(name) BETWEEN 4 AND 6",
                "SUBSTR(name, 1, 1) IN ('W', 'T')", "COALESCE(cat, 'x') = 'x'", "ROUND(price) = 21", "ABS(price - 15) < 6",
                "NULLIF(qty, 0) IS NULL", "TRIM(cat) = ''", "COALESCE(price, 0) > 5 AND LENGTH(name) < 7",
                "REPLACE(name, 'W', 'w') = LOWER(name)")) {
            queries.add(any("SELECT id FROM p WHERE " + condition));
            queries.add(any("SELECT id FROM p WHERE NOT (" + condition + ")"));
        }
        for (String orderBy : List.of(
                "LOWER(name), id", "LENGTH(name) DESC, id", "ABS(price - 15), id", "COALESCE(cat, 'zzz'), id",
                "ROUND(price / 3, 1) DESC, id", "SUBSTR(name, 2, 2), id")) {
            queries.add(ordered("SELECT id FROM p ORDER BY " + orderBy, "id"));
        }
        queries.add(any("SELECT UPPER(cat) AS c, COUNT(*) AS n FROM p GROUP BY UPPER(cat)"));
        queries.add(any("SELECT LENGTH(name) AS len, COUNT(*) AS n, ROUND(AVG(price), 2) AS a FROM p GROUP BY LENGTH(name)"));
        queries.add(any("SELECT SUBSTR(name, 1, 1) AS initial, COUNT(*) AS n FROM p GROUP BY initial"));
        queries.add(any("SELECT cat, ROUND(AVG(price), 1) AS a, COALESCE(SUM(qty), 0) AS s, MAX(LENGTH(name)) AS longest, "
            + "SUM(ABS(price - 15)) AS spread FROM p GROUP BY cat"));
        queries.add(any("SELECT cat FROM p GROUP BY cat HAVING LENGTH(cat) > 4"));
        queries.add(any("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat HAVING ROUND(AVG(price)) > 10"));
        queries.add(ordered("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat ORDER BY COALESCE(SUM(qty), -1), cat", "cat"));
        queries.add(any("SELECT COALESCE(SUM(qty), 0) AS s FROM p WHERE id > 99"));
        queries.add(any("SELECT p.id AS pi, UPPER(p.name) || ':' || COALESCE(o.st, 'none') AS label FROM p LEFT JOIN o ON o.pid = p.id"));
        return queries.stream();
    }

    @ParameterizedTest
    @MethodSource("functionQueries")
    void functionsMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
    }

    @ParameterizedTest
    @MethodSource("aggregateQueries")
    void aggregatesMatchSqlite(Query query) throws Exception {
        assertMatchesSqlite(query);
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
        List<List<String>> expected = runSqlite(query.sqliteSql(), columns);
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

    /**
     * SQLite works in floating point and JsonSQL in decimals, so 40 / 3 is 13.333333333333334
     * in one and 13.33333333333333 in the other. Twelve significant digits is what they share.
     */
    private static String normalize(double value) {
        if (!Double.isFinite(value) || value == 0) {
            return Double.toString(value + 0.0);
        }
        return new java.math.BigDecimal(value).round(new java.math.MathContext(12)).stripTrailingZeros().toPlainString();
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
