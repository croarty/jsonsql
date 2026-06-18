# Materialized Views Guide

JsonSQL supports **materialized CTE views**: expensive `WITH` queries whose results are
persisted on disk and queried later as ordinary tables—without repeating the `WITH` clause.

> TL;DR: Run a heavy query once with `--materialize-view`, then `SELECT * FROM view_name`
> on every subsequent run. Stale views still return stored rows (with a stderr warning);
> use `--rebuild-view` to refresh.

## When materialized views help

Materialized views complement inline CTEs and `--enable-cache`:

| | Inline `WITH` | `--enable-cache` | Materialized view |
|---|---|---|---|
| Named & reusable | No (per query) | No | Yes (`SELECT FROM name`) |
| Survives restarts | No | Yes (parsed JSON cache) | Yes (stored result rows) |
| Listable / manageable | No | No | `--list-materialized-views`, `--show-view` |
| Indexes | N/A | On source files | On the view itself |

Best fit: a **multi-file scan + filter + JOIN** you run often. Materialize once, then
query the compact result set. Combine with `--add-index` on the view for selective filters.

## Managing views

```bash
# Create: the WITH clause must define a CTE whose name matches the view name.
jsonsql --materialize-view expensive \
  --query "WITH expensive AS (SELECT id, category, price FROM catalog_large WHERE price > 100) SELECT * FROM expensive" \
  --data-dir example-data

# Query without WITH (view resolves like a mapped table)
jsonsql -q "SELECT id, category FROM expensive WHERE category = 'Electronics'" --data-dir example-data

# List all views (SQL body, row count, FRESH/STALE)
jsonsql --list-materialized-views --data-dir example-data

# Show one view (includes indexes declared on it)
jsonsql --show-view expensive --data-dir example-data

# Drop view (also removes indexes on that view)
jsonsql --drop-materialized-view expensive --data-dir example-data

# Rebuild after source data changes
jsonsql --rebuild-view expensive --data-dir example-data
jsonsql --rebuild-views --data-dir example-data   # all views
```

Definitions live in `.jsonsql-views.json` (override with `--views-file`). Row data lives
under `.jsonsql-views/<name>.json` inside the data directory.

### Collision rules

- A view name cannot match an existing table mapping.
- Creating a view that already exists fails; use `--rebuild-view` or drop first.
- The `--materialize-view` name must match a CTE in the query (**case-insensitive**, like SQL identifiers).
- An inline `WITH` in your query **shadows** a materialized view of the same name.

### Freshness (stale policy)

When source JSON files change (mtime/size), a view becomes **STALE**. JsonSQL:

1. Prints a warning to **stderr**
2. Serves the **stored** rows (no automatic recompute)

Run `--rebuild-view <name>` to refresh. Freshness is based on backing mapped tables
referenced in the stored CTE SQL—not on the view data file itself.

Freshness is evaluated against the **`--data-dir` recorded at build time** (stored in the
view definition), not the `--data-dir` on `--list-materialized-views` or `--show-view`.
If those differ, you get a stderr warning but the status is still correct. View row data
lives under `<build-time-data-dir>/.jsonsql-views/`; use that same `--data-dir` when
querying the view.

Views created before this behavior was added (no stored `dataDirectory`) fall back to the
current CLI `--data-dir` for freshness. Rebuild once to pick up the stored path and
relative-path fingerprints.

## Indexes on materialized views

Indexes on views use a **single-file summary** for the whole view store. Pruning means
“skip loading the entire view” when the predicate cannot match any row—useful when the
materialized result is large but selective filters apply.

```bash
jsonsql --add-index expensive category --data-dir example-data
jsonsql -q "SELECT id FROM expensive WHERE category = 'Tools'" --data-dir example-data
```

See [Indexing Guide](INDEXING.md) for predicate rules (`=`, `IN`, ranges, `UNNEST`).

## Hands-on: `catalog-large` dataset

The bundled `example-data/catalog-large/` tree has **16 partition files** (4 categories ×
4 years, 8 products per file = 128 products). Use it to see materialization pay off:

```bash
# One-time setup (from repo root)
JAR=target/jsonsql-1.4.0.jar
CFG=/tmp/jsonsql-mv-mappings.json
DATA=example-data

java -jar $JAR -c $CFG --add-mapping catalog_large "catalog-large:\$.products[*]" --data-dir $DATA

# Materialize: filter expensive products from all 16 files once
java -jar $JAR -c $CFG --data-dir $DATA \
  --materialize-view premium \
  --query "WITH premium AS (SELECT id, name, category, price, year FROM catalog_large WHERE price >= 200) SELECT * FROM premium"

# Subsequent queries hit stored rows only (not 16 source files)
java -jar $JAR -c $CFG --data-dir $DATA -q "SELECT id, name, price FROM premium WHERE category = 'Electronics' ORDER BY price DESC"

# Optional: index the view for category filters
java -jar $JAR -c $CFG --data-dir $DATA --add-index premium category
```

Compare with the inline CTE (re-scans all partitions every time):

```bash
java -jar $JAR -c $CFG --data-dir $DATA -q \
  "WITH premium AS (SELECT id, name, category, price FROM catalog_large WHERE price >= 200) SELECT id, name FROM premium WHERE category = 'Furniture'"
```

## `--describe` and dry-run

- `--describe <view>` works for materialized views (shows stored CTE SQL as the mapping).
- `--dry-run` resolves materialized view names like mapped tables.

## Related documentation

- [Indexing Guide](INDEXING.md) — file-level pruning (source tables and views)
- [README](README.md) — full CLI reference
