// Reference sidecar for differential fuzzing: reads JSONL requests from stdin, evaluates each with jsonata@2.2.2 and
// writes one JSONL outcome per request to stdout, in the same order.
//   request:  {"id": "...", "expr": "...", "input": <json>?, "bindings": {...}?}
//   outcome:  {"id", "json": "<JSON text>"} | {"id", "undefined": true} | {"id", "error": code, "position", "token"}
//             | {"id", "crash": "<message>"} (an exception that is not a JSONata error)
// Functions in results are written as the string "<function>" (the Kotlin side does the same).
import jsonata from 'jsonata';
import readline from 'node:readline';

process.env.TZ = 'UTC';
const isFn = (v) => v && (typeof v === 'function' || v._jsonata_lambda === true || v._jsonata_function === true);
const replacer = (k, v) => (isFn(v) ? '<function>' : v);
const options = { timeout: 5000 };

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
const queue = [];
let busy = false;

async function handle(line) {
  const c = JSON.parse(line);
  let outcome;
  try {
    const expr = jsonata(c.expr, options);
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
