package io.github.marsinsanity.mainui.emu.setup

/**
 * An INI file ("[Section]" and "Key = Value" lines) edited as text: what MainUI doesn't change
 * keeps its lines as they were. Section and key names compare without case, as Dolphin's and
 * PPSSPP's IniFile do. A leading UTF-8 byte order mark (PPSSPP writes one) is kept.
 */
internal class IniText(text: String) {
    private class Section(val name: String, val lines: MutableList<String>)

    private val bom = text.startsWith("\uFEFF")
    private val preamble = mutableListOf<String>()
    private val sections = mutableListOf<Section>()

    init {
        var cur: Section? = null
        for (line in text.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                cur = Section(t.substring(1, t.length - 1), mutableListOf()).also { sections += it }
            } else if (cur != null) cur.lines += line else preamble += line
        }
        // (split leaves one empty line after the last "\n")
        (sections.lastOrNull()?.lines ?: preamble).let { if (it.lastOrNull()?.isEmpty() == true) it.removeAt(it.size - 1) }
    }

    private fun section(name: String): Section? = sections.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun keyOf(line: String): String? = line.substringBefore('=', "").trim().takeIf { it.isNotEmpty() && !line.trimStart().startsWith(";") }

    fun has(section: String) = section(section) != null

    fun get(section: String, key: String): String? = section(section)?.lines
        ?.lastOrNull { keyOf(it).equals(key, ignoreCase = true) }?.substringAfter('=')?.trim()

    /** [section] holds exactly [entries] from now on (created at the end when missing). */
    fun replaceSection(section: String, entries: List<Pair<String, String>>) {
        val s = section(section) ?: Section(section, mutableListOf()).also { sections += it }
        s.lines.clear()
        entries.forEach { (k, v) -> s.lines += "$k = $v" }
    }

    /** Sets [key] in [section] (created when missing), or removes it when [value] is null. */
    fun set(section: String, key: String, value: String?) {
        val s = section(section) ?: run {
            if (value == null) return
            Section(section, mutableListOf()).also { sections += it }
        }
        val i = s.lines.indexOfFirst { keyOf(it).equals(key, ignoreCase = true) }
        when {
            value == null -> if (i >= 0) s.lines.removeAt(i)
            i >= 0 -> s.lines[i] = "$key = $value"
            else -> {
                // After the section's last entry, not after its trailing blank lines.
                val last = s.lines.indexOfLast { it.isNotBlank() }
                s.lines.add(last + 1, "$key = $value")
            }
        }
    }

    /** The names of its sections, in order. */
    val sectionNames: List<String> get() = sections.map { it.name }

    override fun toString(): String = buildString {
        if (bom) append('\uFEFF')
        preamble.forEach { append(it).append('\n') }
        for (s in sections) {
            append('[').append(s.name).append("]\n")
            s.lines.forEach { append(it).append('\n') }
        }
    }
}
