package su.seaf.archtool.jsonata.catalog

import su.seaf.archtool.jsonata.Example
import su.seaf.archtool.jsonata.FunctionDefinition
import su.seaf.archtool.jsonata.FunctionDoc
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.LocalizedText
import su.seaf.archtool.jsonata.ParamDoc
import su.seaf.archtool.jsonata.ResultDoc
import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.engine.NativeFunction
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The function catalog (story 2 step 2.3, CAP-8; TC-25 with core and manifest functions comes in story 3). */
class CatalogTest {
    @Test
    fun `every standard function is described`() {
        val catalog = FunctionRegistry.standard().catalog()
        assertEquals(65, catalog.entries.size)
        val problems = ArrayList<String>()
        for (e in catalog.entries) {
            val sig = (FunctionRegistry.standard().lookup(e.name) as NativeFunction).signature!!
            if (e.source != FunctionSource.STDLIB) problems += "${e.name}: source ${e.source}"
            if (e.doc.summary.get("ru").isNullOrBlank()) problems += "${e.name}: no summary"
            if (e.doc.params.size != sig.params.size) problems += "${e.name}: ${e.doc.params.size} params described, ${sig.params.size} in ${sig.definition}"
            e.doc.params.filter { it.description.get("ru").isNullOrBlank() }.forEach { problems += "${e.name}: no description of ${it.name}" }
            if (e.doc.result == null) problems += "${e.name}: no result"
            if (e.doc.examples.isEmpty()) problems += "${e.name}: no example"
            if (e.signature != sig.definition) problems += "${e.name}: signature ${e.signature}"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `the examples give the results they show`() {
        val wrong = FunctionRegistry.standard().catalog().entries.flatMap { e ->
            e.doc.examples.mapNotNull { x ->
                val got = try {
                    Jsonata.compile(x.expr).evaluate(null)?.let { JsonWriter.stringify(it) } ?: "undefined"
                } catch (ex: Exception) {
                    "error ${ex.message}"
                }
                if (got != x.result) "${e.name}: ${x.expr} gives $got, the catalog says ${x.result}" else null
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `host functions overrides and JSON`() {
        val registry = FunctionRegistry.create()
            .register(
                FunctionDefinition(
                    "upper2", "<s-:s>", { _, a -> (a[0] as? StringValue)?.let { StringValue.of(it.value.uppercase()) } },
                    FunctionDoc(
                        LocalizedText(mapOf("ru" to "В верхнем регистре", "en" to "Upper case")),
                        listOf(ParamDoc("str", "s", LocalizedText.ru("Строка"), optional = true)),
                        ResultDoc("s"),
                        listOf(Example("\$upper2(\"a\")", "\"A\"")),
                        declaredAt = "host:Upper.kt:10",
                    ),
                ),
            )
            .register(FunctionDefinition("lookup", "<x-s:x>", { _, _ -> null }, FunctionDoc(LocalizedText.ru("Поиск ядра")), source = FunctionSource.CORE, overridesStandard = true))
            .register("bare", "<n:n>", { _, a -> a[0] })
        val catalog = registry.catalog()
        assertEquals(67, catalog.entries.size) // 65 + upper2 + bare; lookup replaces the standard one
        val lookup = catalog.entry("lookup")!!
        assertEquals(FunctionSource.CORE, lookup.source)
        assertTrue(lookup.overridesStandard)
        val bare = catalog.entry("bare")!!
        assertEquals(listOf("arg1:n"), bare.doc.params.map { "${it.name}:${it.type}" }) // from the signature
        assertEquals("n", bare.doc.result?.type)
        assertTrue(catalog.entry("upper2")!!.sideEffects) // host functions by default

        val json = catalog.toJson("ru")
        val parsed = Jsonata.compile(json).evaluate(null) as ObjectValue
        val upper = ((parsed["functions"] as ArrayValue).toList().first { (it as ObjectValue)["name"] == StringValue.of("upper2") }) as ObjectValue
        assertEquals(
            """{"name":"upper2","source":"host","declaredAt":"host:Upper.kt:10","signature":"<s-:s>","summary":{"ru":"В верхнем регистре"},""" +
                """"params":[{"name":"str","type":"s","optional":true,"description":{"ru":"Строка"}}],"result":{"type":"s"},""" +
                """"examples":[{"expr":"${'$'}upper2(\"a\")","result":"\"A\""}],"overridesStandard":false,"sideEffects":true}""",
            JsonWriter.stringify(upper),
        )
        assertTrue(catalog.toJson().contains("\"en\":\"Upper case\""))
        assertEquals("\"A\"", JsonWriter.stringify(Jsonata.compile("\$upper2(\"a\")", CompileOptions(registry)).evaluate(null)!!))
    }
}
