// Reference outcomes of JSON Schema validation as the SEAF core does it (story 3 step 3.2, design §2.3 "JSON Schema"):
// - mode "params": parameters and results of manifest functions, ajv 8 with allErrors, strict by default, no formats,
//   keywords `callback` and `params` (the value is a function);
// - mode "jsonschema": the $jsonschema function, ajv 8 with allErrors, unicodeRegExp false, ajv-formats (full), `$rels`.
// A value {"$function": true} in the data stands for a JSONata function.
// Usage: node ajv-expected.mjs > ../seaf/ajv-expected.jsonl
import Ajv from 'ajv';
import addFormats from 'ajv-formats';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const versions = { ajv: require('ajv/package.json').version, 'ajv-formats': require('ajv-formats/package.json').version };

// strictTypes and strictTuples only log; the log is silenced, validation is the same
const quiet = { log() {}, warn() {}, error: console.error };

function validator(mode) {
    if (mode === 'params') {
        const ajv = new Ajv({ allErrors: true, logger: quiet });
        for (const keyword of ['callback', 'params']) ajv.addKeyword({ keyword, validate: (_schema, data) => typeof data === 'function' });
        return ajv;
    }
    const ajv = new Ajv({ allErrors: true, unicodeRegExp: false, logger: quiet });
    addFormats(ajv);
    ajv.addKeyword('$rels');
    return ajv;
}

const fn = () => {};
const revive = (v) => {
    if (Array.isArray(v)) return v.map(revive);
    if (v && typeof v === 'object') {
        if (v.$function === true && Object.keys(v).length === 1) return fn;
        return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, revive(x)]));
    }
    return v;
};

const S = (schema, ...data) => ({ schema, data });
const both = (schema, ...data) => [{ mode: 'params', ...S(schema, ...data) }, { mode: 'jsonschema', ...S(schema, ...data) }];
const params = (schema, ...data) => [{ mode: 'params', ...S(schema, ...data) }];
const js = (schema, ...data) => [{ mode: 'jsonschema', ...S(schema, ...data) }];
const F = { $function: true };

const cases = [
    // types
    ...both({ type: 'string' }, 'a', 1, null, true, [], {}),
    ...both({ type: 'number' }, 1, 1.5, '1', null),
    ...both({ type: 'integer' }, 1, 1.0, 1.5, 1e300, '1'),
    ...both({ type: 'boolean' }, true, 0),
    ...both({ type: 'null' }, null, 0, ''),
    ...both({ type: 'array' }, [], {}, 'a'),
    ...both({ type: 'object' }, {}, [], null),
    ...both({ type: ['string', 'null'] }, 'a', null, 1),
    // numbers
    ...both({ minimum: 3 }, 2, 3, 'x'),
    ...both({ maximum: 3 }, 4, 3),
    ...both({ exclusiveMinimum: 3 }, 3, 3.5),
    ...both({ exclusiveMaximum: 3 }, 3, 2),
    ...both({ multipleOf: 0.5 }, 1.5, 1.2),
    ...both({ multipleOf: 3 }, 9, 10),
    // strings
    ...both({ minLength: 2 }, 'a', 'ab', '😀'),
    ...both({ maxLength: 1 }, 'ab', '😀', 'é'),
    ...both({ pattern: '^a+$' }, 'aa', 'ab', 1),
    ...both({ pattern: 'b' }, 'abc', 'xyz'),
    ...both({ pattern: '^\\p{L}+$' }, 'abc', 'p{L}', 'pL', 'äö'),
    ...both({ pattern: '^.$' }, '😀', 'a'),
    ...both({ pattern: '^\\d{3}$' }, '123', '١٢٣'),
    ...both({ pattern: '^[а-я]+$' }, 'абв', 'abc'),
    ...both({ pattern: '\\w+@\\w+' }, 'a@b', '@'),
    ...both({ pattern: '^\\u{1F600}$' }, '😀', 'u'.repeat(1)),
    ...both({ pattern: '^\\a$' }, 'a'),
    ...both({ pattern: '^a{2}$' }, 'aa', 'a'),
    ...both({ pattern: 'a{' }, 'a{'),
    ...both({ pattern: '[' }, 'a'),
    ...both({ pattern: '(?<y>\\d{4})-\\k<y>' }, '2020-2020', '2020-2021'),
    ...both({ pattern: '^\\S+$' }, 'a ', 'ab'),
    ...both({ pattern: '^[^]$' }, 'a'),
    ...both({ pattern: '^\\cJ$' }, '\n'),
    ...both({ pattern: '^\\-$' }, '-'),
    ...both({ pattern: '^[\\-a]$' }, '-'),
    ...both({ pattern: '^\\/$' }, '/'),
    ...both({ pattern: '^\\012$' }, '\n'),
    ...both({ pattern: '\\p{Script=Cyrillic}' }, 'я', 'a'),
    ...both({ pattern: '^\\p{Lu}' }, 'Abc', 'abc'),
    // arrays
    ...both({ items: { type: 'number' } }, [1, 2], [1, 'a', 'b'], 'x'),
    ...both({ items: [{ type: 'number' }, { type: 'string' }] }, [1, 'a'], ['a', 1], [1]),
    ...both({ items: [{ type: 'number' }], additionalItems: false }, [1], [1, 2]),
    ...both({ items: [{ type: 'number' }], minItems: 1, additionalItems: { type: 'string' } }, [1, 'a'], [1, 2]),
    ...both({ additionalItems: false }, [1]),
    ...both({ minItems: 2 }, [1], [1, 2]),
    ...both({ maxItems: 1 }, [1, 2]),
    ...both({ uniqueItems: true }, [1, 2], [1, 1], [{ a: 1 }, { a: 1 }], [[1], [1]]),
    ...both({ contains: { type: 'string' } }, [1, 'a'], [1, 2], []),
    // objects
    ...both({ properties: { a: { type: 'number' } } }, { a: 1 }, { a: 'x' }, {}),
    ...both({ required: ['a', 'b'] }, { a: 1 }, {}, { a: 1, b: 2 }, 'x'),
    ...both({ properties: { a: { type: 'number' } }, additionalProperties: false }, { a: 1, b: 2, c: 3 }),
    ...both({ additionalProperties: { type: 'string' } }, { a: 'x', b: 1 }),
    ...both({ patternProperties: { '^x-': { type: 'string' } }, additionalProperties: false }, { 'x-a': 'v', 'x-b': 1, y: 1 }),
    ...both({ propertyNames: { pattern: '^[a-z]+$' } }, { abc: 1, ABC: 2 }),
    ...both({ minProperties: 1 }, {}),
    ...both({ maxProperties: 1 }, { a: 1, b: 2 }),
    ...both({ dependencies: { a: ['b'] } }, { a: 1 }, { a: 1, b: 1 }, { b: 1 }),
    ...both({ dependencies: { a: { required: ['c'] } } }, { a: 1 }, { a: 1, c: 1 }),
    // combinators, const, enum
    ...both({ enum: [1, 'a', null, { x: 1 }] }, 1, 'a', null, { x: 1 }, 2, { x: 2 }),
    ...both({ const: { a: [1, 2] } }, { a: [1, 2] }, { a: [2, 1] }),
    ...both({ const: 1 }, 1.0, '1'),
    ...both({ allOf: [{ type: 'number' }, { minimum: 2 }] }, 3, 1, 'x'),
    ...both({ anyOf: [{ type: 'number' }, { type: 'string' }] }, 1, 'a', null),
    ...both({ oneOf: [{ type: 'number' }, { type: 'integer' }] }, 1.5, 1, 'x'),
    ...both({ not: { type: 'string' } }, 1, 'a'),
    ...both({ if: { type: 'number' }, then: { minimum: 2 }, else: { type: 'string' } }, 3, 1, 'x', null),
    ...both({ if: { properties: { k: { const: 'a' } } }, then: { required: ['a'] } }, { k: 'a' }, { k: 'b' }),
    // references and definitions
    ...both({ definitions: { n: { type: 'number' } }, properties: { a: { $ref: '#/definitions/n' } } }, { a: 1 }, { a: 'x' }),
    ...both({ $ref: '#/definitions/missing' }, 1),
    ...both({ definitions: { t: { type: 'object', properties: { c: { $ref: '#/definitions/t' } } } }, $ref: '#/definitions/t' }, { c: { c: {} } }, { c: { c: 1 } }),
    // booleans and annotations
    ...both(true, 1),
    ...both(false, 1),
    ...both({ properties: { a: false } }, { a: 1 }, {}),
    ...both({ title: 't', description: 'd', default: 1, examples: [1], $comment: 'c', readOnly: true, writeOnly: false, type: 'number' }, 1),
    ...both({ $schema: 'http://json-schema.org/draft-07/schema#', $id: 'http://example.com/s', type: 'number' }, 1),
    ...both({ contentMediaType: 'application/json', contentEncoding: 'base64', type: 'string' }, 'x'),
    // strict mode: unknown keywords, formats, ignored keywords
    ...both({ type: 'string', example: 'x' }, 'x'),
    ...both({ properties: { a: { type: 'string', foo: 1 } } }, { a: 'x' }),
    ...both({ type: 'string', format: 'email' }, 'a@b.c', 'x'),
    ...both({ type: 'string', format: 'unknown-format' }, 'x'),
    ...both({ type: 'number', minLength: 1 }, 1),
    ...both({ minimum: 'x' }, 1),
    ...both({ required: 'a' }, {}),
    ...both({ type: 'strin' }, 'x'),
    ...both({ properties: { a: { required: true } } }, {}),
    ...both({ items: { type: 'number' }, additionalItems: false }, [1]),
    ...both({ $rels: { x: 1 }, type: 'object' }, {}),
    ...both({ nullable: true, type: 'string' }, null),
    // custom keywords of manifest functions
    ...both({ type: 'object', properties: { f: { callback: true } } }, { f: F }, { f: 1 }, {}),
    ...both({ properties: { f: { params: [] } } }, { f: F }, { f: 'x' }),
    // formats of $jsonschema (ajv-formats full)
    ...js({ format: 'date' }, '2020-01-31', '2020-02-30', '2020-1-1', 1),
    ...js({ format: 'time' }, '10:00:00Z', '10:00:00', '25:00:00Z', '10:00:00+03:00'),
    ...js({ format: 'date-time' }, '2020-01-01T10:00:00Z', '2020-01-01 10:00:00Z', '2020-01-01T10:00:00', '2020-13-01T10:00:00Z'),
    ...js({ format: 'duration' }, 'P1D', 'PT1H', 'P', '1D'),
    ...js({ format: 'uri' }, 'http://a.b/c', 'a/b', 'urn:x:y'),
    ...js({ format: 'uri-reference' }, 'a/b', '#x', '\\\\'),
    ...js({ format: 'uri-template' }, 'http://a/{b}', 'http://a/{b'),
    ...js({ format: 'url' }, 'http://a.b/c', 'a.b'),
    ...js({ format: 'email' }, 'a@b.c', 'a@b', 'a b@c.d'),
    ...js({ format: 'hostname' }, 'a.b', '-a', 'a_b'),
    ...js({ format: 'ipv4' }, '1.2.3.4', '256.1.1.1', '1.2.3'),
    ...js({ format: 'ipv6' }, '::1', '1::2::3', 'fe80::1%eth0'),
    ...js({ format: 'regex' }, '^a$', '[', '\\p{L}'),
    ...js({ format: 'uuid' }, '123e4567-e89b-12d3-a456-426614174000', '123e4567'),
    ...js({ format: 'json-pointer' }, '/a/b', 'a/b', '/a~2'),
    ...js({ format: 'relative-json-pointer' }, '0/a', '/a', '1#'),
    ...js({ format: 'byte' }, 'YQ==', 'YQ=', '!'),
    ...js({ format: 'int32' }, 1, 2147483648, 1.5),
    ...js({ format: 'int64' }, 1, 1.5),
    ...js({ format: 'float' }, 1.5),
    ...js({ format: 'double' }, 1.5),
    ...js({ format: 'password' }, 'x'),
    ...js({ format: 'binary' }, 'x'),
    ...js({ format: 'iri' }, 'http://a'),
    ...js({ format: 'idn-email' }, 'a@b.c'),
    ...js({ format: 'email' }, 1),
    ...js({ formatMinimum: '2020-01-01', format: 'date' }, '2019-01-01'),
    // nested errors and paths
    ...both({ type: 'array', items: { type: 'object', properties: { id: { type: 'string', pattern: '^[a-z]+$' }, n: { type: 'integer', minimum: 0 } }, required: ['id'] } },
        [{ id: 'ok', n: 1 }, { id: 'BAD', n: -1 }, { n: 1.5 }, 'x']),
    ...both({ properties: { 'a/b': { type: 'number' }, 'c~d': { type: 'number' } } }, { 'a/b': 'x', 'c~d': 'y' }),
    ...params({ type: 'object', properties: { items: { type: 'array', items: { type: 'number' } }, fn: { callback: true } }, required: ['items'] },
        { items: [1, 2, 3], fn: F }, { fn: 1 }),
    // edge cases of strict mode and error reporting
    ...both({ definitions: { unused: { type: 'string', foo: 1 } }, type: 'number' }, 1),
    ...both({ properties: { a: { anyOf: [{ type: 'number' }, { type: 'string', minLength: 2 }] } } }, { a: 'x' }, { a: null }),
    ...both({ oneOf: [{ minimum: 1 }, { maximum: 5 }] }, 3, 0),
    ...both({ formatMinimum: '2020-01-01' }, '2019-01-01'),
    ...js({ format: 'date', formatMinimum: 1 }, '2019-01-01'),
    ...js({ format: 'email', formatMinimum: 'a' }, 'b'),
    ...js({ format: 'date-time', formatExclusiveMaximum: '2020-01-01T00:00:00Z' }, '2020-01-01T00:00:00Z', '2019-12-31T23:59:59Z'),
    ...js({ format: 'time', formatMaximum: '12:00:00' }, '13:00:00', '11:00:00'),
    ...both(5, 1),
    ...both({ items: { type: 'integer' }, uniqueItems: true }, [1, 2, 1, 2]),
    ...both({ uniqueItems: true }, [1, 'a', 1, 'a'], [{ a: [1] }, 2, { a: [1] }]),
    ...both({ dependencies: { a: ['b', 'c'] } }, { a: 1 }),
    ...both({ enum: [[1, 2], { a: 1 }] }, [1, 2], [2, 1]),
    ...both({ type: 'object', required: ['x'], properties: { x: { type: 'array', minItems: 2, items: { enum: ['a', 'b'] } } } }, { x: ['c'] }),
    ...both({ maxLength: 2 }, '😀😀'),
    ...both({ type: 'integer' }, -0, 9007199254740993, 0.1),
    ...both({ multipleOf: 0.01 }, 0.07, 1.15),
    ...both({ const: null }, null, 0),
    ...both({ items: [{ type: 'number' }, { type: 'number' }], additionalItems: { type: 'string' } }, [1, 2, 'a', 3]),
    ...both({ patternProperties: { '^\\p{Lu}': { type: 'number' } } }, { Abc: 'x', abc: 'y' }),
    ...params({ properties: { cb: { callback: true }, n: { type: 'number' } }, required: ['cb'] }, { n: 'x' }),
    ...both({ not: { not: { type: 'string' } } }, 1),
    ...both({ allOf: [{ required: ['a'] }, { required: ['b'] }] }, {}),
    ...both({ properties: { a: { $ref: '#' } }, type: 'object' }, { a: { a: 1 } }),
    // a class escape as a range bound (review RV3-14): SyntaxError with u (params), a literal hyphen without (jsonschema)
    ...both({ type: 'string', pattern: '[\\d-z]' }, '-', '5', 'a'),
    ...both({ type: 'string', pattern: '^[\\w-.]+$' }, 'a-b.c', 'a b'),
];

console.log(JSON.stringify({ header: true, ...versions, cases: cases.length }));
let id = 0;
for (const c of cases) {
    id++;
    const out = { id: `ajv-${String(id).padStart(3, '0')}`, mode: c.mode, schema: c.schema };
    let validate;
    try {
        validate = validator(c.mode).compile(c.schema);
    } catch (e) {
        console.log(JSON.stringify({ ...out, compileError: true, message: e.message }));
        continue;
    }
    out.results = c.data.map((d) => {
        const valid = validate(revive(d));
        return valid
            ? { data: d, valid: true }
            : { data: d, valid: false, errors: validate.errors.map((e) => ({ instancePath: e.instancePath, keyword: e.keyword, params: e.params, message: e.message })) };
    });
    console.log(JSON.stringify(out));
}
