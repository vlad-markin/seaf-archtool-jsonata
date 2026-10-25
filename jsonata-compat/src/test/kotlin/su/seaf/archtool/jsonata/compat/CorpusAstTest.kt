package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** SEAF corpus (compat/corpus, snapshots in _corpus.json): every AST or error equals jsonata-js 2.2.2. */
class CorpusAstTest {
    @Test
    fun `corpus AST matches the reference`() {
        val file = File(System.getProperty("jsonata.compat"), "snapshots/ast/_corpus.json")
        assumeTrue(file.exists(), "no corpus snapshots")
        val snapshots = ObjectMapper().readTree(file)
        val failures = ArrayList<String>()
        var total = 0
        snapshots.properties().forEach { (id, entry) ->
            total++
            val expr = entry.get("expr").asText()
            val error = entry.get("error")
            try {
                val actual = KotlinEngine.ast(expr)
                if (error != null) failures += "$id: expected ${error.get("code").asText()}, got AST"
                else if (normalize(actual) != normalize(entry.get("ast"))) failures += "$id: AST differs"
            } catch (e: EngineError) {
                if (error == null || error.get("code").asText() != e.code || (error.get("position") != null && error.get("position").asInt() != e.position)) {
                    failures += "$id: unexpected error ${e.code} at ${e.position}"
                }
            }
        }
        println("corpus: ${total - failures.size}/$total")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }
}
