#!/bin/bash
# Stop hook: runs the test suite when src/ or pom.xml has uncommitted changes.
# Exit 2 sends the failure back to Claude. A state that has already been tested
# is skipped, so a test Claude can't fix doesn't block stopping forever.
set -u

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
input=$(cat)

changes=$(git status --porcelain -- src pom.xml)
[ -z "$changes" ] && exit 0

state=$(
  {
    git diff HEAD -- src pom.xml
    git ls-files --others --exclude-standard -z -- src | xargs -0 -r git hash-object
  } | git hash-object --stdin
)

mkdir -p target
stamp=target/claude-stop-hook.state
last=$(cat "$stamp" 2>/dev/null)
[ "$last" = "passed $state" ] && exit 0
if [ "$last" = "failed $state" ] && printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  exit 0
fi

if command -v mvn >/dev/null 2>&1; then mvn=mvn; else mvn=./mvnw; fi

log=target/claude-stop-hook.log
if "$mvn" -q -B test >"$log" 2>&1; then
  echo "passed $state" >"$stamp"
  exit 0
fi

echo "failed $state" >"$stamp"
{
  echo "mvn test failed. Fix this before finishing. Full log: $log"
  grep -E '\[ERROR\]' "$log" | head -30
} >&2
exit 2
