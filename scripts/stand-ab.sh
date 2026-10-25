#!/usr/bin/env bash
# A/B of two engine revisions on the performance stand (docs/perf-method.md), processes interleaved:
#   scripts/stand-ab.sh <rev A> <rev B> [size=10000] [rounds=5] [queries=all] [mode=plain]
# Each revision's jsonata-core and jsonata-json are built in a git worktree under jsonata-bench/build/ab/; the runner
# is the current StandRunner, so revisions older than the stand work too (mode plain). Every round runs A and B once
# each, the order alternating; the table shows the median of the per-round medians and the spread of the rounds.
# Modes: plain, listener (a listener without breakpoints), profiler (CAP-12; story 5 and later on both sides).
set -euo pipefail
A=${1:?rev A}; B=${2:?rev B}; SIZE=${3:-10000}; ROUNDS=${4:-5}; QUERIES=${5:-}; MODE=${6:-plain}
ROOT=$(git rev-parse --show-toplevel)
WORK="$ROOT/jsonata-bench/build/ab"
mkdir -p "$WORK"
cd "$ROOT"
./gradlew -q :jsonata-bench:installDist
LIB="$ROOT/jsonata-bench/build/install/jsonata-bench/lib"
BASE_CP=$(ls "$LIB"/*.jar | grep -v -e '/jsonata-core' -e '/jsonata-json' | tr '\n' ':')
DATA="$ROOT/jsonata-bench/build/stand/model-$SIZE.json"
if [ ! -f "$DATA" ]; then
  java -cp "$LIB/*" su.seaf.archtool.jsonata.bench.GenerateKt "$SIZE" "$DATA"
fi
build() { # side rev
  local dir="$WORK/$1"
  if [ ! -d "$dir" ]; then git worktree add --detach "$dir" "$2" >/dev/null; else git -C "$dir" checkout -q --detach "$2"; fi
  (cd "$dir" && ./gradlew -q :jsonata-core:jar :jsonata-json:jar)
  echo "$(ls "$dir"/jsonata-core/build/libs/jsonata-core-*.jar | head -1):$(ls "$dir"/jsonata-json/build/libs/jsonata-json-*.jar | head -1)"
}
CP_A=$(build A "$A"); CP_B=$(build B "$B")
rm -f "$WORK/A.jsonl" "$WORK/B.jsonl"
ONLY=${QUERIES:+--only=$QUERIES}
for r in $(seq 1 "$ROUNDS"); do
  if [ $((r % 2)) -eq 1 ]; then ORDER="A B"; else ORDER="B A"; fi
  for side in $ORDER; do
    if [ "$side" = A ]; then CP="$CP_A"; else CP="$CP_B"; fi
    java -Xms3g -Xmx3g -XX:+UseG1GC -Xss16m -cp "$BASE_CP$CP" su.seaf.archtool.jsonata.bench.StandRunner \
      --data="$DATA" --size="$SIZE" --queries="$ROOT/jsonata-bench/queries" --out="$WORK/$side.jsonl" --mode="$MODE" $ONLY 2>/dev/null
    echo "round $r $side done ($(uptime | sed 's/.*load average: //'))" >&2
  done
done
node -e '
const fs = require("fs");
const read = (f) => fs.readFileSync(f, "utf8").split("\n").filter(Boolean).map(JSON.parse);
const med = (s) => { s = s.slice().sort((a, b) => a - b); return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2; };
const by = (rs) => rs.reduce((m, r) => ((m[r.query] ||= []).push(r.eval_ms_median), m), {});
const a = by(read(process.argv[1])), b = by(read(process.argv[2]));
console.log("| query | A, ms | B, ms | Δ | A rounds | B rounds |\n|---|---:|---:|---:|---|---|");
for (const q of Object.keys(a)) {
  if (!b[q]) continue;
  const ma = med(a[q]), mb = med(b[q]);
  const rng = (s) => Math.min(...s).toFixed(2) + "–" + Math.max(...s).toFixed(2);
  console.log(`| ${q} | ${ma.toFixed(2)} | ${mb.toFixed(2)} | ${((mb / ma - 1) * 100).toFixed(1)} % | ${rng(a[q])} | ${rng(b[q])} |`);
}' "$WORK/A.jsonl" "$WORK/B.jsonl"
