# Changelog

## 2.0.0 - 2026-10-30

This release adds aggregates, and makes JsonSQL follow SQL where it used to differ. Several of those changes alter results for existing queries, so read [Upgrading from 1.x](#upgrading-from-1x) first.

### Breaking changes

- `WHERE` follows SQL three-valued logic, so `NOT` no longer returns rows with NULL or missing values.
- An unquoted word in a comparison is now a field, not text.
- `UNION` columns match by position and take the first branch's names.
- NULL and missing values sort together in `ORDER BY`.
- `ORDER BY` prefers a SELECT alias over a field with the same name.
- Data files with several JSON documents or text after the document now fail. They used to be cut short with no warning.
- Bare words, `+1`, `.5` and hexadecimal numbers in data files now fail. They used to be read as text.
- Parameter values can no longer change a query, so some values that used to work now fail.
- Showing or dropping an index or view that doesn't exist now exits with 1, with the message on stderr.
- Cache and index file names changed, so indexes need rebuilding to speed up queries again.

### Added

- `GROUP BY`, `HAVING`, and `COUNT`, `SUM`, `AVG`, `MIN` and `MAX`, with `DISTINCT`
- `BETWEEN` and `NOT BETWEEN`, which also use indexes
- `OFFSET` and `LIMIT m, n`
- Comparisons in either order and between fields: `20 < price`, `price > stock`
- `ORDER BY` on an alias or a position
- `LIKE ... ESCAPE`
- Double-quoted field names
- Field and table names as parameters after `SELECT`, `FROM`, `JOIN` and `ORDER BY`
- UTF-8 files with a byte order mark, and UTF-16 with one
- Comments at the start of data files
- MIT licence and third-party notices

### Fixed

- A `JOIN` returned no rows if the joined table's field came first in `ON`
- A failed view rebuild permanently deleted the view
- With `--enable-cache`, a `JOIN` CTE and a `LEFT JOIN` CTE could return each other's rows
- `price = NULL` compared against the text `"NULL"`
- `'O''Brien'` was compared with its quotes still doubled
- Arithmetic and functions in `WHERE`, and expressions in `ORDER BY`, silently matched nothing. They're now rejected.

### Changed

- Joins use a hash lookup. A join of 20,000 rows on each side went from 51 seconds to under a second.
- Data file errors name the file, with the line and column for invalid JSON.
- SQL syntax errors are shorter and no longer show internal class names.
- JsonPath parses data files with Jackson rather than json-smart.
- Requires the same Java 21 as before, and CI also tests Java 25.

## Upgrading from 1.x

After upgrading, run this once in each project that uses indexes. Results are correct without it, but indexes won't speed up queries until you do:

```bash
jsonsql --rebuild-indexes
```

Old cache files are ignored. `--clear-cache` removes them.

Then check your saved queries and scripts for the following.

### Queries that return different rows with no error

| Look for | What changed | To fix |
|---|---|---|
| `NOT` around a condition, or `NOT IN` with `NULL` in the list | Rows where the field is NULL or missing are left out | Add `OR field IS NULL` to keep them |
| An unquoted word as a value: `category = Tools` | `Tools` is now a field, so this probably finds nothing | Use single quotes: `category = 'Tools'` |
| `ORDER BY` on a nullable field | Explicit `null` now sorts with missing values, first ascending and last with `DESC` | Filter with `IS NOT NULL` if you don't want them |
| An alias with the same name as a field, used in `ORDER BY` | The sort now uses the alias | Rename the alias, or order by a qualified field |

### Queries that return differently shaped results

| Look for | What changed | To fix |
|---|---|---|
| `UNION` branches with different column names | Every row uses the first branch's names | Read the first branch's names, or alias the branches to what you want |
| `UNION` rows that differ only in column name | They now count as duplicates, so `UNION` removes them | Use `UNION ALL`, or keep a column that tells them apart |

### Things that now fail with an error

| Look for | What changed | To fix |
|---|---|---|
| Files with several JSON documents, including newline-delimited JSON | These used to return only the first document, so results were already incomplete | Combine them into one JSON array |
| Files with text after the JSON | This text used to be ignored | Remove the extra text |
| Bare words, `+1`, `.5` or hexadecimal in a data file | These used to be read as text | Quote them, or write valid numbers |
| A whitespace-only data file | This used to give no rows | Remove the file, or add valid JSON |
| `--param` with plain text in a condition: `price > ${n}` with `n=abc` | Rejected, so a value can't change the query | Put the placeholder in quotes for text: `'${n}'` |
| `--param` with a field name in a condition: `WHERE ${field} = 1` | Rejected | Save a query for each field |
| `--param` with more than a name in `ORDER BY`: `sort=price DESC` | Rejected | Use a second parameter for the direction |
| Arithmetic or functions in `WHERE`: `price + 1 > 20` | These used to match nothing, and are now rejected | Rewrite as `price > 19` |
| Expressions in `ORDER BY` | These used to have no effect, and are now rejected | Order by a field, alias or position |

### Scripts

- `--show-view`, `--drop-index` and `--drop-materialized-view` now exit with 1 for something that doesn't exist, and write the "No such" message to stderr rather than stdout.
- SQL syntax error messages are shorter, so check anything that reads their text.

### Precision

Decimals beyond about 17 significant digits are now rounded, because data files are parsed by Jackson as doubles.
