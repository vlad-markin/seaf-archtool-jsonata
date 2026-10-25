// Runs compat/traps/*.jsonl on jsonata@2.2.2 and writes compat/traps/expected.jsonl (one outcome per case).
import jsonata from 'jsonata';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.TZ = 'UTC';
// expected.jsonl is recorded on Node 22: $power's last bits differ on other V8 versions (NUM-11)
if (!process.versions.node.startsWith('22.')) {
  console.error(`traps.mjs must run on Node 22 (found ${process.versions.node}), see .nvmrc`);
  process.exit(1);
}
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../traps');
const out = [];
for (const file of ['divergences.jsonl', 'targeted.jsonl', 'review.jsonl']) {
  for (const line of fs.readFileSync(path.join(dir, file), 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const c = JSON.parse(line);
    if (c.functions) continue; // host function cases are covered by EngineTest
    let outcome;
    try {
      const expr = jsonata(c.expr, c.options);
      if (c.bounds) {
        let depth = 0; const start = Date.now();
        const check = () => {
          if (depth > c.bounds.depth || Date.now() - start > c.bounds.timelimit) throw { code: 'U1001' };
        };
        expr.assign(Symbol.for('jsonata.__evaluate_entry'), () => { depth++; check(); });
        expr.assign(Symbol.for('jsonata.__evaluate_exit'), () => { depth--; check(); });
      }
      const r = await expr.evaluate(c.input);
      outcome = r === undefined ? { undefined: true } : { json: JSON.stringify(r) };
    } catch (e) {
      outcome = e.code ? { error: e.code, position: e.position ?? null, token: e.token ?? null } : { crash: String(e.message || e) };
    }
    out.push(JSON.stringify({ id: c.id, ...outcome }));
  }
}
fs.writeFileSync(path.join(dir, 'expected.jsonl'), out.join('\n') + '\n');
console.log(`${out.length} outcomes written`);
