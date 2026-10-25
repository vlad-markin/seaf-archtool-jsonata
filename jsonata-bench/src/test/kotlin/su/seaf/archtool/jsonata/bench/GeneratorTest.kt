package su.seaf.archtool.jsonata.bench

import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The model is byte for byte that of gen.js of the dashjoin evaluation (SHA-256 taken from `node gen.js N`). */
class GeneratorTest {
    private fun sha(n: Int): String {
        val w = StringWriter()
        Generator.write(n, w)
        return MessageDigest.getInstance("SHA-256").digest(w.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `same bytes as gen js`() {
        assertEquals("055dbefeb02eb8a3d7994f7964b15f981879bef0c70cfe0f2df70ddf80c505fa", sha(1000))
        assertEquals("a42c11b65e98d6d6a0c59e312a90cdd8ff9206649f1c3830c3d7e2f55b10362e", sha(10000))
    }

    @Test
    fun `every query has a file and parses`() {
        val queries = Queries.load(File(System.getProperty("jsonata.root"), "jsonata-bench/queries"))
        assertEquals(15, queries.size)
        for (q in queries) su.seaf.archtool.jsonata.Jsonata.compile(q.expr)
    }
}
