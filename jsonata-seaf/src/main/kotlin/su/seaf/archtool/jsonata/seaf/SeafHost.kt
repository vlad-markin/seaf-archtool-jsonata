package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.value.Value

/**
 * What the SEAF core functions need from the host (the service, the IDE plugin). The only I/O of an expression is
 * [loadSource]; the contract is in `docs/seaf-host.md`. Implementations are called on the evaluating thread (which may
 * be a stack segment thread of the engine, so `ThreadLocal` values of the host are not visible) and may block.
 */
public interface SeafHost {
    /**
     * `$parsesource(manifest, source)`: loads and parses a data source. The host allows only sources inside the lake
     * or from its configured list, limits the response size, checks the rights of [request], waits at most
     * [CallContext.remainingNanos] and treats an interrupt as cancellation. A failure is an exception of the host:
     * the engine passes it unchanged.
     */
    public fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: CallContext): Value?

    /** `$manifestschema()`: the JSON Schema of the manifest; asked lazily, at most once per environment. */
    public fun manifestSchema(): Value?

    /** `$versions()`: versions of the portal parts. */
    public fun versions(): Value?

    /**
     * `$log(content, tag)`: writes [content] with an optional [tag] (null when not given) to the host log, in the order
     * of the core; turning logging off is a setting of the host. The call gives no value to the expression.
     */
    public fun log(content: Value?, tag: Value?)
}
