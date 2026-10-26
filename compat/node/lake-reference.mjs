// Reference side of the lake gate (design §2.1 p. 3): evaluates expressions with the JSONata driver of the SEAF core,
// in a checkout of seaf-archtool-core at the commit of compat/seaf/core-ref.json. Run from that checkout:
//   TZ=UTC MANIFEST=/path/manifest.json BACKEND_URL=... [BACKEND_BODY=...] [TIMING=N] \
//     npx tsx /path/to/engine/compat/node/lake-reference.mjs < cases.jsonl > outcomes.jsonl
// cases:    {"id", "expr", "self"?, "params"?} per line
// outcomes: {"id", "outcome": "<canonical text>", "ms"?: <median of TIMING repeats>} per line
// The core's code is not copied: it is imported from the current directory (the checkout). $parsesource goes to the
// local backend with the same request as the engine side (jsonata-tools BackendClient), so both see the same data.
// The driver's API (expression(expr, self, params).evaluate(manifest)) and the dataset driver hook follow the design;
// check them against the core on the first local run (docs/compat-policy.md, "Lake gate").
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';
import { canonical, canonicalError } from './canonical.mjs';

if (process.env.TZ !== 'UTC') { console.error('lake-reference: run with TZ=UTC (DEV-07)'); process.exit(2); }
const manifest = JSON.parse(fs.readFileSync(process.env.MANIFEST, 'utf8'));
const backendUrl = process.env.BACKEND_URL;
const body = process.env.BACKEND_BODY || '{"query": %q, "cache": "none", "role": %r}';
const timing = parseInt(process.env.TIMING || '0', 10);

const memo = new Map();
async function parseSource(source) {
  const key = JSON.stringify(source ?? null);
  if (!memo.has(key)) {
    const res = await fetch(backendUrl, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: body.replace('%q', JSON.stringify(`($parsesource(${key}))`)).replace('%r', 'null'),
    });
    if (!res.ok) throw new Error(`backend ${res.status}`);
    const text = (await res.text()).trim();
    memo.set(key, text ? JSON.parse(text) : undefined);
  }
  return memo.get(key);
}

// stubs the driver needs outside the backend
process.env.VUE_APP_DOCHUB_JSONATA_LOG_FUNC_ENABLE ??= 'n';
process.env.VUE_APP_DOCHUB_PERF_LOGGER_ENABLE ??= 'n';
const driverModule = await import(pathToFileURL(path.resolve('src/global/jsonata/driver.mjs')).href);
const driver = driverModule.default ?? driverModule;
if (typeof driver.setDatasetDriver === 'function') driver.setDatasetDriver({ parseSource });
else globalThis.getDatasetDriver = () => ({ parseSource });

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of rl) {
  if (!line.trim()) continue;
  const c = JSON.parse(line);
  let outcome;
  const times = [];
  try {
    const run = () => driver.expression(c.expr, c.self, c.params).evaluate(manifest);
    outcome = canonical(await run());
    for (let i = 0; i < timing; i++) {
      const t = process.hrtime.bigint();
      await run();
      times.push(Number(process.hrtime.bigint() - t) / 1e6);
    }
  } catch (e) {
    outcome = e && e.code ? canonicalError(e) : `crash ${(e && e.message) || e}`;
  }
  times.sort((a, b) => a - b);
  const ms = times.length ? times[Math.floor(times.length / 2)] : undefined;
  process.stdout.write(JSON.stringify({ id: c.id, outcome, ms }) + '\n');
}
