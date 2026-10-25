// Rough timing of jsonata@2.2.2 on compat/bench/cases.json (same scaling as the Kotlin Bench).
// Usage: node bench.mjs [iterations]
import jsonata from 'jsonata';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cases = JSON.parse(fs.readFileSync(path.join(root, 'bench/cases.json'), 'utf8'));
const iterations = Number(process.argv[2] || 10);

function load(c) {
  if (!c.dataset) return undefined;
  const data = JSON.parse(fs.readFileSync(path.join(root, 'jsonata-js/test-suite/datasets', c.dataset + '.json'), 'utf8'));
  if (c.scale) {
    let parent = data;
    for (const k of c.scale.path.slice(0, -1)) parent = parent[k];
    const key = c.scale.path[c.scale.path.length - 1];
    const items = parent[key];
    const out = [];
    for (let i = 0; i < c.scale.times; i++) for (const it of items) out.push(JSON.parse(JSON.stringify(it)));
    parent[key] = out;
  }
  return data;
}

const results = [];
for (const c of cases) {
  const input = load(c);
  const expr = jsonata(c.expr);
  const times = [];
  let result;
  for (let i = 0; i < iterations + 3; i++) {
    const t0 = process.hrtime.bigint();
    result = await expr.evaluate(input);
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    if (i >= 3) times.push(ms);
  }
  times.sort((a, b) => a - b);
  results.push({ name: c.name, medianMs: times[Math.floor(times.length / 2)], result: JSON.stringify(result).slice(0, 60) });
}
console.log(JSON.stringify(results, null, 1));
