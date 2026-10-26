// Canonical text of an outcome for the lake gate, the same as jsonata-tools/.../gate/Canonical.kt (design §2.1 p. 4):
// JSON with ECMAScript number lexemes and the key order of the value, undefined marked at any depth (#undefined),
// functions marked (#function), errors as "error CODE @position token".
const isFn = (v) => typeof v === 'function' || (v && (v._jsonata_lambda === true || v._jsonata_function === true));

export function canonical(v) {
  if (v === undefined) return '#undefined';
  if (isFn(v)) return '#function';
  if (Array.isArray(v)) return '[' + v.map((x) => canonical(x)).join(',') + ']';
  if (v !== null && typeof v === 'object') {
    return '{' + Object.keys(v).map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  }
  if (typeof v === 'number' && !Number.isFinite(v)) return 'null'; // as JSON.stringify
  return JSON.stringify(v);
}

export function canonicalError(e) {
  const pos = e.position === undefined || e.position === null ? 'null' : String(e.position);
  const tok = e.token === undefined || e.token === null ? 'null' : String(e.token);
  return `error ${e.code} @${pos} ${tok}`;
}
