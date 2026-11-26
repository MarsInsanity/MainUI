package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.data.GameListXml
import org.xmlpull.v1.XmlPullParser

/**
 * An app's SharedPreferences file (shared_prefs/<name>.xml) read and written back the way
 * Android writes it. Entries MainUI doesn't change are written back unchanged. The app must be
 * stopped: it keeps its own copy in memory and writes that back over ours.
 */
internal class PrefsXml(text: String) {
    /** One entry: its tag (string, int, boolean, long, float, set) and value(s). */
    private class Entry(val tag: String, val value: String?, val set: List<String>? = null)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        if (text.isNotBlank()) {
            val p = GameListXml.newParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(text.reader())
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.depth == 2) {
                    val tag = p.name
                    val name = p.getAttributeValue(null, "name")
                    when (tag) {
                        "string" -> entries[name] = Entry(tag, p.nextText())
                        "set" -> {
                            val items = ArrayList<String>()
                            while (p.next() != XmlPullParser.END_TAG || p.depth > 2) {
                                if (p.eventType == XmlPullParser.START_TAG && p.name == "string") items += p.nextText()
                            }
                            entries[name] = Entry(tag, null, items)
                        }
                        else -> entries[name] = Entry(tag, p.getAttributeValue(null, "value"))
                    }
                }
                ev = p.next()
            }
        }
    }

    fun has(name: String) = name in entries
    fun string(name: String): String? = entries[name]?.takeIf { it.tag == "string" }?.value
    fun value(name: String): String? = entries[name]?.value
    fun stringSet(name: String): List<String>? = entries[name]?.set
    val names: Set<String> get() = entries.keys

    fun putString(name: String, v: String) { entries[name] = Entry("string", v) }
    fun putInt(name: String, v: Int) { entries[name] = Entry("int", v.toString()) }
    fun putLong(name: String, v: Long) { entries[name] = Entry("long", v.toString()) }
    fun putBoolean(name: String, v: Boolean) { entries[name] = Entry("boolean", v.toString()) }
    fun putFloat(name: String, v: Float) { entries[name] = Entry("float", v.toString()) }
    fun putSet(name: String, v: Collection<String>) { entries[name] = Entry("set", null, v.toList()) }
    fun remove(name: String) { entries.remove(name) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    override fun toString(): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((name, e) in entries) {
            val n = esc(name)
            when (e.tag) {
                "string" -> append("    <string name=\"$n\">${esc(e.value ?: "")}</string>\n")
                "set" -> {
                    append("    <set name=\"$n\">\n")
                    e.set.orEmpty().forEach { append("        <string>${esc(it)}</string>\n") }
                    append("    </set>\n")
                }
                else -> append("    <${e.tag} name=\"$n\" value=\"${esc(e.value ?: "")}\" />\n")
            }
        }
        append("</map>\n")
    }
}
