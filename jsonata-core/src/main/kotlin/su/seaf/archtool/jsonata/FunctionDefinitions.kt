package su.seaf.archtool.jsonata

/** Where a registered function comes from (CAP-4, CAP-8). */
public enum class FunctionSource {
    /** The jsonata-js 2.2.2 standard library. */
    STDLIB,

    /** Functions of the SEAF core (module `jsonata-seaf`). */
    CORE,

    /** Native functions of the host application. */
    HOST,

    /** Functions of the `functions` section of a manifest, written in JSONata. */
    MANIFEST,
}

/** A text in several languages, by language tag (`"ru"`, `"en"`). */
public class LocalizedText(public val texts: Map<String, String>) {
    /** The text in [lang], or in Russian, or in any language; null when there is none. */
    public fun get(lang: String? = null): String? = (lang?.let { texts[it] } ?: texts["ru"] ?: texts.values.firstOrNull())

    public companion object {
        /** A text in Russian, the language of the current descriptions. */
        @JvmStatic
        public fun ru(text: String): LocalizedText = LocalizedText(mapOf("ru" to text))
    }
}

/** A parameter of a documented function: [type] in the signature syntax (`s`, `a<n>`, `f`…). */
public class ParamDoc @JvmOverloads constructor(
    public val name: String,
    public val type: String,
    public val description: LocalizedText,
    public val optional: Boolean = false,
)

/** The result of a documented function: [type] in the signature syntax. */
public class ResultDoc @JvmOverloads constructor(
    public val type: String,
    public val description: LocalizedText? = null,
)

/** An example call and its result as JSON text (`undefined` for no result). */
public class Example(public val expr: String, public val result: String)

/**
 * Documentation of a function for the catalog (CAP-8): what IDEs, the portal and agents show. [manifestId] is the id of
 * a manifest function, [declaredAt] an opaque place given by the host (file and line of the declaration).
 */
public class FunctionDoc @JvmOverloads constructor(
    public val summary: LocalizedText,
    public val params: List<ParamDoc> = emptyList(),
    public val result: ResultDoc? = null,
    public val examples: List<Example> = emptyList(),
    public val manifestId: String? = null,
    public val declaredAt: String? = null,
)

/**
 * A function to register (CAP-4). [signature] uses the jsonata-js 2.2.2 syntax (`<s-n?:s>`), null for no checks.
 * [overridesStandard] allows the name of a standard library function (CAP-7; the core's `$lookup` is the first such
 * case): without it that registration fails with X0007. [sideEffects] marks functions that must not run in a debugger
 * watch expression. [arity] is what higher-order functions see (how many of value, index, array they pass); by default
 * the number of parameters of the signature.
 */
public class FunctionDefinition @JvmOverloads constructor(
    public val name: String,
    public val signature: String?,
    public val implementation: NativeImplementation,
    public val doc: FunctionDoc,
    public val source: FunctionSource = FunctionSource.HOST,
    public val overridesStandard: Boolean = false,
    public val sideEffects: Boolean = source == FunctionSource.HOST,
    public val arity: Int? = null,
)

/**
 * A name defined more than once along a registry chain (CAP-7 p. 5): [sources] from the outermost registry to the
 * innermost, [effective] the one an expression calls.
 */
public class NameConflict(
    public val name: String,
    public val sources: List<FunctionSource>,
    public val effective: FunctionSource,
) {
    override fun toString(): String = "$name: ${sources.joinToString(" -> ")} (effective $effective)"
}
