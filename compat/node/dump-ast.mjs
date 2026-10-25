// Reference AST snapshots: runs jsonata-js 2.2.2 `ast()` over every expression of the vendored test-suite
// (and of compat/corpus/*.jsonata when present) and writes compat/snapshots/ast/<group>.json.
//
// Snapshot entry per case id: {"expr": ..., "ast": ...} or {"expr": ..., "error": {"code", "position", "token"}}.
// Serialization: RegExp -> {"@regex": source, "flags": flags}; a function (signature.validate) -> {"@function": name};
// undefined fields are dropped (as JSON.stringify does).
// Comparison on the Kotlin side is structural; object key order is not significant.
//
// Usage (from compat/node): npm ci && node dump-ast.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import jsonata from 'jsonata';

const here = path.dirname(fileURLToPath(import.meta.url));
const suite = path.join(here, '..', 'jsonata-js', 'test-suite', 'groups');
const corpus = path.join(here, '..', 'corpus');
const out = path.join(here, '..', 'snapshots', 'ast');

const version = JSON.parse(fs.readFileSync(path.join(here, 'node_modules', 'jsonata', 'package.json'), 'utf8')).version;
if (version !== '2.2.2') throw new Error(`jsonata 2.2.2 expected, found ${version}`);

function plain(value, seen = new Set()) {
    if (value instanceof RegExp) return { '@regex': value.source, flags: value.flags };
    if (Array.isArray(value) || (value && typeof value === 'object')) {
        if (seen.has(value)) throw new Error('cyclic AST');
        seen.add(value);
        let result;
        if (Array.isArray(value)) {
            result = value.map((v) => plain(v, seen) ?? null);
        } else {
            result = {};
            for (const [k, v] of Object.entries(value)) {
                const p = plain(v, seen);
                if (p !== undefined) result[k] = p;
            }
        }
        seen.delete(value);
        return result;
    }
    // Lambda signatures carry a compiled `validate` function next to `definition`; keep only a marker.
    if (typeof value === 'function') return { '@function': value.name || true };
    return value;
}

function entry(expr) {
    try {
        return { expr, ast: plain(jsonata(expr).ast()) };
    } catch (e) {
        if (!e.code) throw e;
        return { expr, error: { code: e.code, position: e.position, token: e.token } };
    }
}

function write(name, snapshot) {
    fs.writeFileSync(path.join(out, `${name}.json`), JSON.stringify(snapshot, null, 1) + '\n');
}

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
let count = 0;
for (const group of fs.readdirSync(suite).sort()) {
    const dir = path.join(suite, group);
    const snapshot = {};
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
        const parsed = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
        const cases = Array.isArray(parsed) ? parsed : [parsed];
        cases.forEach((c, i) => {
            const id = Array.isArray(parsed) ? `${file.slice(0, -5)}[${i}]` : file.slice(0, -5);
            const expr = c['expr-file'] ? fs.readFileSync(path.join(dir, c['expr-file']), 'utf8') : c.expr;
            snapshot[id] = entry(expr);
            count++;
        });
    }
    write(group, snapshot);
}
if (fs.existsSync(corpus)) {
    const snapshot = {};
    for (const file of fs.readdirSync(corpus).filter((f) => f.endsWith('.jsonata')).sort()) {
        snapshot[file] = entry(fs.readFileSync(path.join(corpus, file), 'utf8'));
        count++;
    }
    if (Object.keys(snapshot).length) write('_corpus', snapshot);
}
console.log(`jsonata ${version}: ${count} AST snapshots written to ${path.relative(process.cwd(), out)}`);
