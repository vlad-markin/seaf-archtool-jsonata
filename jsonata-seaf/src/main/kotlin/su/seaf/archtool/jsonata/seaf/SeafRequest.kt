package su.seaf.archtool.jsonata.seaf

/**
 * The request an evaluation runs for (the user, the role, the rights), opaque to the engine: the semantics of an
 * expression do not depend on it. Core functions hand it to [SeafHost.loadSource].
 */
public class SeafRequest(public val attributes: Map<String, Any?>) {
    override fun toString(): String = "SeafRequest$attributes"
}
