package su.seaf.archtool.jsonata.bench

import java.io.File

/** `Generate <components> <out.json>`: writes one model of the stand (scripts/stand-ab.sh uses it). */
fun main(args: Array<String>) {
    val n = args[0].toInt()
    val out = File(args[1])
    out.parentFile?.mkdirs()
    out.bufferedWriter().use { Generator.write(n, it) }
}
