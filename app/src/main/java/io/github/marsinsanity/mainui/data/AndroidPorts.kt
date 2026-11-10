package io.github.marsinsanity.mainui.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android apps in the Ports system. Each one is a small text file in a rom root's "ports" folder,
 * "<Name>.android", whose first line is the app's package name ("com.example.doom64"), so the
 * Ports system is the ports folder like every other system: its names, art (images/<Name>.png) and
 * gamelist.xml live there, and other kinds of ports (PortMaster's .sh scripts, later) can sit
 * beside them. Lines starting with "#" are comments; "package=" in front of the name is allowed.
 */
object AndroidPorts {
    const val SYSTEM = "ports"
    const val EXT = "android"
    private const val FOLDER = "ports"

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    /** A port file that starts an Android app. */
    fun isPort(f: File?): Boolean = f != null && f.extension.equals(EXT, ignoreCase = true)

    fun isPackageName(s: String) = PACKAGE.matches(s)

    /** Package names read from port files, by path and modification time. */
    private val known = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** The package [f] starts (read once per change of the file), or null when it names none. */
    fun packageOf(f: File): String? {
        val m = f.lastModified()
        known[f.absolutePath]?.let { (at, pkg) -> if (at == m) return pkg }
        val pkg = runCatching {
            if (f.length() > 4096) null
            else f.readLines().asSequence().map { it.removePrefix("﻿").trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.removePrefix("package=")?.trim()?.takeIf(::isPackageName)
        }.getOrNull()
        known[f.absolutePath] = m to pkg
        return pkg
    }

    /** The package [f] starts if it was read already (no file access: for drawing). */
    fun knownPackage(f: File?): String? = f?.let { known[it.absolutePath]?.second }

    /** The ports folders of [roots] (any letter case), where they exist. */
    fun dirs(roots: List<File>): List<File> =
        roots.mapNotNull { r -> r.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(FOLDER, ignoreCase = true) } }

    /** Every port file in [dirs] and their sub folders (a level deep), each with its package. */
    fun files(dirs: List<File>): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        for (d in dirs) {
            for (f in d.listFiles().orEmpty()) {
                if (f.name.startsWith(".")) continue
                val inside = if (f.isDirectory) f.listFiles().orEmpty().filter { it.isFile } else listOf(f)
                for (p in inside) if (isPort(p)) packageOf(p)?.let { out += p to it }
            }
        }
        return out
    }

    /** Package names that are ports (so they aren't Android games or plain apps as well). */
    fun packages(dirs: List<File>): Set<String> = files(dirs).mapTo(HashSet()) { it.second }

    /** "Doom 64: Remastered!" -> "Doom 64 - Remastered!" (a name every file system takes). */
    fun fileName(label: String): String {
        val clean = label.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " - ").replace(Regex("""\s+"""), " ")
            .replace(Regex("""( - )+"""), " - ").trim(' ', '-', '.')
        return clean.ifEmpty { "Port" }.take(80)
    }

    /**
     * Adds [pkg] (an app called [label]) to the Ports system: its file in the first ports folder of
     * [roots], created in the first root when there is none. Already a port: that file. [write]
     * writes a file's text (with a root fallback in the app). Returns the port file.
     */
    fun add(roots: List<File>, label: String, pkg: String, write: (File, String) -> Unit = { f, t -> f.writeText(t) }): File {
        require(isPackageName(pkg)) { "not a package name: $pkg" }
        files(dirs(roots)).firstOrNull { it.second == pkg }?.let { return it.first }
        val dir = dirs(roots).firstOrNull() ?: File(roots.firstOrNull() ?: throw java.io.IOException("No Roms folder found"), FOLDER)
        if (!dir.isDirectory) dir.mkdirs() // (else [write] makes it: with root where MainUI can't)
        val base = fileName(label)
        var f = File(dir, "$base.$EXT")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).$EXT").also { n++ }
        write(f, "$pkg\n")
        known.remove(f.absolutePath)
        return f
    }

    /** Takes [pkg] out of the Ports system (deletes its port files). Returns the files that couldn't be deleted. */
    fun remove(roots: List<File>, pkg: String): List<File> =
        files(dirs(roots)).filter { it.second == pkg }.map { it.first }.filter { !it.delete() && it.exists() }
}
