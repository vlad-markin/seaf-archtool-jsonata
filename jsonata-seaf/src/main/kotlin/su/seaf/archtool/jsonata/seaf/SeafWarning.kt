package su.seaf.archtool.jsonata.seaf

/** Something in the manifest the environment skipped (a manifest function id that cannot be called). */
public class SeafWarning(public val functionId: String, public val message: String) {
    override fun toString(): String = message
}
