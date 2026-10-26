// Reference sidecar for differential fuzzing: reads JSONL requests from stdin, evaluates each with jsonata@2.2.2 and
// writes one JSONL outcome per request to stdout, in the same order.
//   request:  {"id": "...", "expr": "...", "input": <json>?, "bindings": {...}?}
// A case that does not finish is cut by the Kotlin side (DiffFuzz: reference-timeout, the sidecar is restarted).
//   outcome:  {"id", "json": "<JSON text>"} | {"id", "undefined": true} | {"id", "error": code, "position", "token"}
//             | {"id", "crash": "<message>"} (an exception that is not a JSONata error)
// Functions in results are written as the string "<function>" (the Kotlin side does the same).
import jsonata from 'jsonata';
import fs from 'node:fs';
import readline from 'node:readline';

// the reference runs on the Node major of package.json (engines): number formatting and Math differ between majors
const need = />=\s*(\d+)/.exec(JSON.parse(fs.readFileSync(new URL('./package.json', import.meta.url))).engines.node)?.[1];
if (need && process.versions.node.split('.')[0] !== need) {
  console.error(`sidecar: Node ${need} is needed, found ${process.versions.node}`);
  process.exit(3);
}

process.env.TZ = 'UTC';
const isFn = (v) => v && (typeof v === 'function' || v._jsonata_lambda === true || v._jsonata_function === true);
const replacer = (k, v) => (isFn(v) ? '<function>' : v);

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
const queue = [];
let busy = false;

async function handle(line) {
  const c = JSON.parse(line);
  let outcome;
  try {
    const expr = jsonata(c.expr);
    const r = await expr.evaluate(c.input, c.bindings);
    outcome = r === undefined ? { undefined: true } : { json: JSON.stringify(r, replacer) };
    if (outcome.json === undefined) outcome = { undefined: true };
  } catch (e) {
    outcome = e && e.code ? { error: e.code, position: e.position ?? null, token: e.token ?? null }
      : { crash: String((e && e.message) || e) };
  }
  process.stdout.write(JSON.stringify({ id: c.id, ...outcome }) + '\n');
}

async function drain() {
  if (busy) return;
  busy = true;
  while (queue.length) await handle(queue.shift());
  busy = false;
}

rl.on('line', (line) => { if (line.trim()) { queue.push(line); drain(); } });
rl.on('close', async () => { await drain(); });
