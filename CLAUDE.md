# JsonSQL

Java 21 command-line tool that queries JSON files with SQL-like syntax. Built with Maven; the entry point is `com.jsonsql.JsonSqlCli`.

## Commands

```bash
mvn test                                  # full suite, about 30 seconds
mvn test -Dtest=QueryExecutorTest         # one class, while iterating
mvn package                               # builds target/jsonsql-<version>.jar
java -jar target/jsonsql-1.6.0.jar --help
```

`./mvnw` runs the same commands with the Maven version pinned in `.mvn/wrapper/`.

The course in `jsonsql-course/` is generated. Edit `modules/`, `_base.html` or `_footer.html`, never `index.html`, then run `bash build.sh` from that directory.

## Code map

`src/main/java/com/jsonsql/`:

- `JsonSqlCli` - Picocli options and command wiring
- `query/` - parsing (`QueryParser`), execution (`QueryExecutor`), filtering (`WhereEvaluator`)
- `config/` - mappings, saved queries, parameters, cache
- `index/` - declared indexes and file-level pruning
- `view/` - materialized CTE views
- `introspection/` - `--describe`
- `output/` - JSON and CSV output

## Constraints

- JSqlParser stays on 4.x. Version 5 changes the AST and visitor APIs.
- Reject unimplemented SQL at parse time with a clear error. Don't let it silently match nothing.
- Config files (`.jsonsql-mappings.json`, `.jsonsql-queries.json`, `.jsonsql-indexes.json`, `.jsonsql-views.json`) resolve from the current directory, not `--data-dir`.
- `.jsonsql-mappings.json` is git-ignored, so a fresh clone has no mappings.

## Every code change needs tests and docs

Implementing, fixing or changing behaviour isn't finished until all three are done. Don't treat tests or docs as follow-ups, and don't wait to be asked. Questions with no code change need neither.

1. **Code:** a minimal, focused diff.
2. **Tests:** cover the happy path, edge cases and error paths, in JUnit 5 under `src/test/java/`, matching the existing style.
   - Pure logic: unit tests on the class (e.g. `CsvFormatterTest`)
   - CLI wiring: `JsonSqlCliTest`
   - Query behaviour: the `QueryExecutor`, parser or evaluator tests
3. **Docs:** update every doc that would otherwise be wrong. At minimum check:
   - `README.md`: features, CLI options, examples, Future Enhancements
   - `EXAMPLES.md`, `QUICK-START.md`: runnable examples
   - `COMPREHENSIVE-REVIEW.md`: capability status, gaps, test counts
   - any other `*.md` that mentions the behaviour

Doc rules:

- Never show unsupported syntax as if it works.
- Move items between "future" and "implemented" in the same change as the code.
- Runnable examples must match real CLI flags, field names and fixtures.

A Stop hook runs `mvn test` when `src/` or `pom.xml` has uncommitted changes. It doesn't check docs, so those remain your responsibility.

## Releasing a new version

The version appears in `pom.xml`, `JsonSqlCli.java` and the docs, so search for the old number and change all of them. The test count appears several times in `COMPREHENSIVE-REVIEW.md`.
