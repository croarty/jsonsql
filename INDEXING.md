# Indexing Guide

JsonSQL supports **declared indexes** that let selective queries skip backing files
which cannot contain a matching row. This document explains what indexes do, when they
help, how to manage them, and the exact rules that keep results correct.

> TL;DR: Indexes give **file-level pruning**. They shine on tables backed by **many
> files** (a partitioned directory tree). They are always safe: anything uncertain falls
> back to a full scan, so an indexed query returns exactly what `--no-index` returns.

## When indexing helps (and when it doesn't)

A table mapping can point at a single file or at a **directory** that is loaded
recursively (every `.json` file in the tree). Indexes prune at the granularity of a
**whole file**:

- **Big win — many files:** `WHERE region = 'North'` against a directory of 500 daily
  files only needs to read the files that actually contain `North`. The rest are skipped
  without being parsed.
- **No win — one big file:** a single JSON document must be read and parsed in full to
  evaluate any predicate, so file-level pruning cannot skip anything inside it. (Row-level
  / byte-offset indexing for single documents is a possible future enhancement and is
  **not** implemented today.)

Indexes are complementary to `--enable-cache`:

| | Cache (`--enable-cache`) | Index (`--add-index`) |
|---|---|---|
| Stores | Parsed JSON rows per file | Per-file value summary (distinct values, min/max) |
| Speeds up | Re-reading the **same** files | **Skipping** files that can't match |
| Used when | The same source is queried repeatedly | A selective predicate targets an indexed field |

## Managing indexes

```bash
# Declare and build an index on a field. The field may be a dotted path.
jsonsql --add-index products category
jsonsql --add-index products price
jsonsql --add-index products specifications.material

# Array fields: indexed as the union of element values across the file.
jsonsql --add-index products tags            # array of strings
jsonsql --add-index products reviews.rating  # array of objects -> sub-property

# List declared indexes with a FRESH / STALE status per index.
jsonsql --list-indexes

# Rebuild after data changes.
jsonsql --rebuild-index products category   # one
jsonsql --rebuild-indexes                    # all declared indexes

# Remove an index (definition + summary file).
jsonsql --drop-index products category
```

Notes:
- `--add-index` both **declares** the index and **builds** it immediately.
- All index commands honor `--data-dir`, `--config`, and `--indexes-file`.
- Index **definitions** are stored in `.jsonsql-indexes.json` (override with
  `--indexes-file`). Per-file **summaries** are stored under `.jsonsql-index/` inside the
  data directory (alongside the optional `.jsonsql-cache/`).

## How a query uses indexes

When you run a query and indexes are declared, JsonSQL:

1. Resolves the FROM table's files (recursively for directory mappings).
2. Splits the `WHERE` clause on top-level `AND` into individual predicates.
3. For each predicate that targets an indexed field, loads the index summary and removes
   files whose summary **proves** no row can match.
4. Loads and queries only the surviving files. The rest of the pipeline (WHERE, JOIN,
   UNNEST, ORDER BY, LIMIT, projection) is unchanged.

Pass `--no-index` to bypass indexes entirely for a single run.

### Supported predicates

Pruning is attempted for these predicate shapes when they appear as **mandatory `AND`
conjuncts** on an indexed field:

- Equality: `field = 'value'`
- Membership: `field IN ('a', 'b', ...)`
- Ranges: `field > n`, `field >= n`, `field < n`, `field <= n` (either operand order)

Everything else is simply not used for pruning (the file is kept):

- `OR` between predicates → the whole table is scanned (an `OR` branch can match anything).
- `LIKE` / `ILIKE`, `IS NULL`, `!=`, `NOT IN`, `BETWEEN`, functions, etc.
- Predicates on non-indexed fields.

Because predicates are AND-ed, a single usable conjunct is enough to prune. For example,
in `WHERE category = 'Furniture' AND name LIKE '%pro%'`, the indexed `category = 'Furniture'`
still prunes files even though the `LIKE` part is ignored by the planner.

### Arrays and UNNEST

Array fields are summarized as the **union of element values** seen in each file, and the
index is marked *multi-valued*. The planner maps an `UNNEST` element column back to the
indexed source field:

```sql
-- tags is ["wireless","audio", ...]; indexed field is "tags"
SELECT id, tag
FROM products, UNNEST(tags) AS t(tag)
WHERE tag = 'wireless';      -- prunes files whose tags union lacks "wireless"

-- reviews is [{rating: 5, ...}, ...]; indexed field is "reviews.rating"
SELECT id
FROM products, UNNEST(reviews) AS r(review)
WHERE review.rating = 5;     -- prunes files whose ratings union lacks 5
```

### What `min`/`max` buy you

For range predicates the planner uses the per-file `min`/`max`:

- `field > v` can match a file only if its `max > v`.
- `field < v` can match a file only if its `min < v` (and similarly for `>=` / `<=`).

So `WHERE price >= 30` skips any file whose maximum price is below 30. `min`/`max` are
also used to reject out-of-range equality when a file's distinct-value list was dropped
due to high cardinality (see below).

## Hands-on: the bundled `catalog` and `sales` datasets

The repository ships a partitioned dataset under `example-data/` designed specifically to
exercise file-level pruning:

```
example-data/
  catalog/                      # mapping: catalog -> catalog:$.products[*]
    electronics/{2023,2024}.json
    furniture/{2023,2024}.json
    tools/{2023,2024}.json      # 6 files; each file = one category + one year
  sales/                        # mapping: sales -> sales:$.orders[*]
    north/{q1,q2}.json
    south/{q1,q2}.json          # 4 files; each file = one region + one quarter
```

Each catalog product has: `id`, `name`, `brand`, `category`, `price`, `inStock`, `year`,
`region`, `tags[]`, `specifications.{material,weightKg,color}`, and
`reviews[].{user,rating,verified}`. Each sales order has: `orderId`, `productId` (references
a catalog `id`), `quantity`, `unitPrice`, `status`, `channel`, `region`, and `quarter`.

> The commands below keep config files out of the repo root by pointing `--config` and
> `--indexes-file` at scratch files. Adjust paths as you like.

```bash
JAR=target/jsonsql-1.4.0.jar
CFG=/tmp/jsonsql-mappings.json
IDX=/tmp/jsonsql-indexes.json

# 1) Map the two partitioned tables (directory mappings load every .json recursively)
java -jar $JAR -c $CFG --add-mapping catalog "catalog:\$.products[*]"  --data-dir example-data
java -jar $JAR -c $CFG --add-mapping sales   "sales:\$.orders[*]"      --data-dir example-data

# 2) Build indexes (scalar, dotted, and array fields)
for f in category year price specifications.material tags reviews.rating; do
  java -jar $JAR -c $CFG --indexes-file $IDX --add-index catalog "$f" --data-dir example-data
done
java -jar $JAR -c $CFG --indexes-file $IDX --add-index sales status  --data-dir example-data
java -jar $JAR -c $CFG --indexes-file $IDX --add-index sales region  --data-dir example-data
java -jar $JAR -c $CFG --indexes-file $IDX --list-indexes --data-dir example-data
```

Now run queries — each comment notes which file(s) survive pruning:

```bash
A="java -jar $JAR -c $CFG --indexes-file $IDX --data-dir example-data -q"

# Equality on the partition key -> reads ONLY catalog/furniture/* (2 files)
$A "SELECT id, name, price FROM catalog WHERE category = 'Furniture'"

# Range on price -> skips files whose max price < 150 (e.g. furniture/2023 stays, tools vary)
$A "SELECT id, name, price FROM catalog WHERE price >= 150 ORDER BY price DESC"

# IN list -> only the named categories' files
$A "SELECT id, category FROM catalog WHERE category IN ('Tools', 'Electronics')"

# Nested scalar (dotted index) -> only files containing an 'oak' product
$A "SELECT id, name FROM catalog WHERE specifications.material = 'oak'"

# Array of strings via UNNEST -> 'gaming' only appears in electronics/2024
$A "SELECT id, tag FROM catalog, UNNEST(tags) AS t(tag) WHERE tag = 'gaming'"

# Array of objects via UNNEST -> rating = 2 only appears in furniture/2024 & tools/2023
$A "SELECT id FROM catalog, UNNEST(reviews) AS r(review) WHERE review.rating = 2"

# AND: one indexed conjunct still prunes even though LIKE is ignored by the planner
$A "SELECT id, name FROM catalog WHERE category = 'Electronics' AND name LIKE '%Webcam%'"

# OR disables pruning (full scan) but returns the same rows
$A "SELECT id, category FROM catalog WHERE category = 'Tools' OR price > 500"

# JOIN delivered North orders to their catalog products (sales pruned by region/status)
$A "SELECT o.orderId, o.quantity, p.name, p.price FROM sales o JOIN catalog p ON o.productId = p.id WHERE o.region = 'North' AND o.status = 'delivered'"
```

Confirm correctness by appending `--no-index` to any query: the results are identical, only
the set of files read differs. After editing a file, `--list-indexes` will show it `STALE`;
run `--rebuild-indexes` to restore pruning.

## Correctness and freshness

Pruning never changes results. A file is removed from a query **only** when:

- a summary for that file exists in the index, **and**
- the summary is **fresh** — its stored fingerprint (last-modified time + size) still
  matches the file on disk, **and**
- the summary proves the predicate cannot match.

Any deviation keeps the file:

- **New/uncovered file** added after the index was built → not in the summary → loaded.
- **Modified file** → fingerprint mismatch → treated as stale → loaded.
- **Changed mapping JSONPath** → the index no longer applies → full scan.
- **Mixed value types** in a file → no reliable `min`/`max` → ranges don't prune that file.

`--list-indexes` reports `FRESH` only when the current file set and every fingerprint
match the built summary; otherwise it reports `STALE`, and you should
`--rebuild-index`/`--rebuild-indexes`.

## Indexes on materialized views

You can declare indexes on a **materialized view** the same way as on a mapped table:

```bash
jsonsql --add-index premium category --data-dir example-data
```

The view's stored rows live in a single file under `.jsonsql-views/`. The index summary
covers that file as a whole: pruning skips loading the entire view when the predicate
cannot match any row. Rebuild the view (`--rebuild-view`) after source data changes, then
`--rebuild-index` if needed.

See [Materialized Views](MATERIALIZED-VIEWS.md) for the full workflow.

## Limitations (current scope)

- **File-level only.** Pruning skips whole files; it does not skip rows inside a file.
  A single large document gains nothing.
- **FROM table only.** Pruning applies to the query's FROM table, not to JOINed tables.
- **Manual freshness.** Indexes are not auto-rebuilt on data changes; stale files simply
  fall back to scanning. Rebuild to restore pruning.
- **High-cardinality fields.** Above an internal cap, the per-file distinct-value list is
  dropped and only `min`/`max` are kept, so equality pruning relies on range bounds.
