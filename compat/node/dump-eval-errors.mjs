// Reference error positions: evaluates every test-suite case that expects an error on jsonata@2.2.2 and writes
// compat/pass/eval-errors.jsonl, one line per case: {"id", "code", "position", "token"}.
//
// Each case runs in a fresh Node process: jsonata-js keeps the position and name of the last call on the shared
// objects of its built-in functions, so an error raised inside a higher-order function can report a position left by
// an earlier case of the same process (reference instability, docs/compat-policy.md). Cases with `timelimit` and
// `depth` are timeboxed as compat/jsonata-js/run-test-suite.js does (U1001).
//
// A timeboxed case is marked "unstable": it may stop on the time limit at whatever call is running then.
//
// Usage (from compat/node, Node 22): npm ci && node dump-eval-errors.mjs
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

process.env.TZ = 'UTC';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', 'jsonata-js', 'test-suite');
const out = path.join(here, '..', 'pass', 'eval-errors.jsonl');

/** Test-suite cases in the order and with the ids of TestSuite.kt: `<group>/<file>` or `<group>/<file>[<i>]`. */
function cases() {
  const groupsDir = path.join(root, 'groups');
  const list = [];
  for (const group of fs.readdirSync(groupsDir).filter((g) => fs.statSync(path.join(groupsDir, g)).isDirectory()).sort()) {
    for (const file of fs.readdirSync(path.join(groupsDir, group)).filter((f) => f.endsWith('.json')).sort()) {
      const spec = JSON.parse(fs.readFileSync(path.join(groupsDir, group, file), 'utf8'));
      const name = `${group}/${file.slice(0, -5)}`;
      const items = Array.isArray(spec) ? spec.map((c, i) => [`${name}[${i}]`, c]) : [[name, spec]];
      for (const [id, c] of items) {
        if (c['expr-file']) c.expr = fs.readFileSync(path.join(groupsDir, group, c['expr-file']), 'utf8');
        list.push({ id, c });
      }
    }
  }
  return list;
}

function dataset(c) {
  if ('data' in c) return c.data;
  if (c.dataset === null || c.dataset === undefined) return undefined;
  return JSON.parse(fs.readFileSync(path.join(root, 'datasets', c.dataset + '.json'), 'utf8'));
}

async function runOne(id) {
  const { default: jsonata } = await import('jsonata');
  const { c } = cases().find((x) => x.id === id);
  try {
    const expr = jsonata(c.expr);
    if ('timelimit' in c && 'depth' in c) {
      let depth = 0;
      const start = Date.now();
      const check = () => {
        if (c.depth > 0 && depth > c.depth) throw { code: 'U1001' };
        if (Date.now() - start > c.timelimit) throw { code: 'U1001' };
      };
      expr.assign(Symbol.for('jsonata.__evaluate_entry'), (e, i, env) => { if (env.isParallelCall) return; depth++; check(); });
      expr.assign(Symbol.for('jsonata.__evaluate_exit'), (e, i, env) => { if (env.isParallelCall) return; depth--; check(); });
    }
    await expr.evaluate(dataset(c), c.bindings);
    return { id, code: null };
  } catch (e) {
    const r = { id, code: e.code ?? null, position: e.position ?? null, token: e.token ?? null };
    // a timeboxed case may stop on the time limit at whatever call is running: position and token are not stable
    if ('timelimit' in c) r.unstable = true;
    return r;
  }
}

if (process.argv[2] === '--one') {
  process.stdout.write(JSON.stringify(await runOne(process.argv[3])));
} else {
  const version = JSON.parse(fs.readFileSync(path.join(here, 'node_modules', 'jsonata', 'package.json'), 'utf8')).version;
  if (version !== '2.2.2') throw new Error(`jsonata 2.2.2 expected, found ${version}`);
  const ids = cases().filter(({ c }) => c.code || (c.error && c.error.code)).map((x) => x.id);
  const results = new Map();
  let next = 0;
  const worker = async () => {
    while (next < ids.length) {
      const id = ids[next++];
      const text = await new Promise((resolve, reject) => {
        const p = spawn(process.execPath, ['--stack-size=4000', fileURLToPath(import.meta.url), '--one', id], { env: process.env });
        let s = '';
        p.stdout.on('data', (d) => { s += d; });
        p.on('close', (code) => (code === 0 ? resolve(s) : reject(new Error(`${id}: exit ${code}`))));
      });
      results.set(id, JSON.parse(text));
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, os.cpus().length) }, worker));
  const lines = ids.map((id) => JSON.stringify(results.get(id)));
  fs.writeFileSync(out, lines.join('\n') + '\n');
  console.log(`${lines.length} error cases written to ${path.relative(process.cwd(), out)}`);
}
