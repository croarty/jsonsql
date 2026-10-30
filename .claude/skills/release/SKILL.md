---
name: release
description: Prepare a new JsonSQL version. Use when asked to bump the version, cut a release or update the changelog.
---

# Release JsonSQL

Takes the new version as an argument, such as `/release 2.1.0`. If none is given, propose one.

## Choose the version

- **Major:** an existing query, file or script can behave differently, even if it's an improvement.
- **Minor:** new features that change nothing for existing use. Something that used to be an error can start working.
- **Patch:** fixes only.

List the changes since the last release with `git log`. If any could change an existing result, say so and make it a major version.

## Steps

1. Start from an up-to-date `master` with a clean working tree, and create a `release-<version>` branch. Check CI and CodeQL are passing on `master`.
2. Replace the old version everywhere it appears. Search rather than rely on a list, because new docs may mention it:

   ```bash
   git grep -n "<old version>"
   ```

   It's in `pom.xml`, `JsonSqlCli.java`, `CLAUDE.md` and several docs. Don't change other projects' version numbers that happen to match.
3. Add a section to the top of `CHANGELOG.md`, with `## <version> - <date>`. Group it under Breaking changes, Added, Fixed and Changed. For each breaking change, say what changed, how to spot affected queries, and how to fix them.
4. Run the full build, which also checks coverage:

   ```bash
   ./mvnw -B -ntp clean verify
   ```

5. Update the test counts from that run. `COMPREHENSIVE-REVIEW.md` has the number of tests in four places and the number of test classes in one. `jsonsql-course/modules/05-when-things-go-wrong.html` has the number of test files, so run `bash build.sh` in `jsonsql-course/` afterwards.
6. Check the jar reports the new version:

   ```bash
   java -jar target/jsonsql-<version>.jar --version
   ```

7. Confirm the old version is gone with `git grep` again, except in `CHANGELOG.md`.
8. Commit, push and open a pull request.

Don't tag or publish a release until the pull request is merged and the user asks for it. A tag is visible to everyone and is awkward to undo.
