// jsonata-js 2.2.2 side of the performance stand (story 6; jsonata-bench/.../StandRunner.kt is the engine side).
// Same arguments and record format as StandRunner:
//   node --expose-gc stand.mjs --data=model.json --size=N --queries=dir --out=results.jsonl [--dump=dir] [--only=a,b]
//   node stand.mjs --data=model.json --size=N --queries=dir --once=name   (heap bisection: exit 0 when it passes)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import v8 from 'node:v8';
import { PerformanceObserver } from 'node:perf_hooks';
import jsonata from 'jsonata';

const args = Object.fromEntries(process.argv.slice(2).map((a) => {
    const [k, ...v] = a.replace(/^--/, '').split('=');
    return [k, v.join('=')];
}));
const size = parseInt(args.size, 10);
const index = JSON.parse(fs.readFileSync(path.join(args.queries, 'index.json'), 'utf8'));
const queries = index.map((q) => ({ ...q, expr: fs.readFileSync(path.join(args.queries, `${q.name}.jsonata`), 'utf8').trim() }));
const data = JSON.parse(fs.readFileSync(args.data, 'utf8'));

if (args.once) {
    const q = queries.find((x) => x.name === args.once);
    await jsonata(q.expr).evaluate(data);
    process.exit(0);
}

if (typeof global.gc !== 'function') { console.error('run with --expose-gc'); process.exit(2); }
const only = args.only ? new Set(args.only.split(',')) : null;
const warmupDefault = size <= 10000 ? 10 : size <= 50000 ? 5 : 3;
const itersDefault = size <= 10000 ? 20 : size <= 50000 ? 10 : 5;
const warmup = parseInt(args.warmup || warmupDefault, 10);
const iters = parseInt(args.iters || itersDefault, 10);
let gcCount = 0;
new PerformanceObserver((list) => { gcCount += list.getEntries().length; }).observe({ entryTypes: ['gc'] });
const median = (s) => (s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2);
const pct = (s, p) => s[Math.min(s.length - 1, Math.ceil(p * s.length) - 1)];
const tick = () => new Promise((r) => setImmediate(r));
global.gc();
const dataHeap = process.memoryUsage().heapUsed;
console.error(`[js] size=${size} dataHeap=${(dataHeap / 1048576).toFixed(0)}MB`);
for (const q of queries) {
    if (q.sizes && !q.sizes.includes(size)) continue;
    if (only && !only.has(q.name)) continue;
    const ct = [];
    let expr;
    for (let i = 0; i < 200; i++) {
        const s = process.hrtime.bigint();
        expr = jsonata(q.expr);
        ct.push(Number(process.hrtime.bigint() - s) / 1e6);
    }
    ct.sort((a, b) => a - b);
    let result;
    let error = null;
    const times = [], heapDeltas = [];
    let warmDone = 0, warmSpent = 0, measSpent = 0, gcs = 0, peak = 0;
    try {
        for (let i = 0; ; i++) {
            const measuring = warmDone >= warmup || (warmDone >= 1 && warmSpent > 15000);
            if (measuring && (times.length >= iters || (times.length >= 3 && measSpent > 20000))) break;
            global.gc();
            await tick();
            const g0 = gcCount;
            const h0 = process.memoryUsage().heapUsed;
            const s = process.hrtime.bigint();
            const r = await expr.evaluate(data);
            const e = Number(process.hrtime.bigint() - s) / 1e6;
            const h1 = process.memoryUsage().heapUsed;
            peak = Math.max(peak, v8.getHeapStatistics().used_heap_size);
            if (i === 0) result = r;
            if (!measuring) { warmDone++; warmSpent += e; } else { measSpent += e; times.push(e); heapDeltas.push(h1 - h0); gcs += gcCount - g0; }
        }
    } catch (e) {
        error = (e.code || '') + ' ' + e.message;
    }
    if (args.dump) fs.writeFileSync(path.join(args.dump, `${q.name}-${size}.js.json`), JSON.stringify(error ? { __error: error } : (result === undefined ? null : result)));
    const ts = times.slice().sort((a, b) => a - b);
    const hs = heapDeltas.slice().sort((a, b) => a - b);
    const rec = {
        engine: 'jsonata-js-2.2.2', node: process.version, query: q.name, size, warmup_done: warmDone, iters_done: times.length,
        compile_ms_median: median(ct), error, loadavg1: os.loadavg()[0],
        eval_ms_median: ts.length ? median(ts) : null, eval_ms_p95: ts.length ? pct(ts, 0.95) : null,
        eval_ms_min: ts.length ? ts[0] : null, eval_ms_max: ts.length ? ts[ts.length - 1] : null,
        heap_delta_mb_median: hs.length ? median(hs) / 1048576 : null, gc_during_eval_total: gcs,
        heap_peak_mb: peak / 1048576, data_heap_mb: dataHeap / 1048576, rss_peak_mb: process.resourceUsage().maxRSS / 1024,
        times_ms: times,
    };
    fs.appendFileSync(args.out, JSON.stringify(rec) + '\n');
    console.error(`[js] ${q.name.padEnd(18)} n=${size} median=${rec.eval_ms_median?.toFixed(2)}ms p95=${rec.eval_ms_p95?.toFixed(2)}ms${error ? ' ERROR ' + error : ''}`);
}
