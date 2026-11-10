package io.github.marsinsanity.mainui.emu

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Disc images kept in .zip files for emulators that can't open archives (hakuX only takes
 * .iso / .xiso / .cso / .cci): the image is unpacked once beside the zip, as
 * "<zip name>.<image type>", and launched from there afterwards.
 */
object ZipImages {
    /** The image an earlier launch unpacked beside [zip], or null. */
    fun unpacked(zip: File, types: Set<String>): File? {
        val dir = zip.parentFile ?: return null
        val names = dir.list()?.associateBy { it.lowercase() } ?: return null
        for (t in types) names["${zip.nameWithoutExtension}.$t".lowercase()]?.let { n ->
            val f = File(dir, n)
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    /** The zip entry to unpack: the biggest file of one of [types]; null when there is none. */
    fun entry(zip: File, types: Set<String>): Pair<String, Long>? = ZipFile(zip).use { z ->
        z.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in types }
            .maxByOrNull { it.size }?.let { it.name to it.size }
    }

    /** How many images of [types] [zip] holds (a multi-disc zip has several: only one is unpacked). */
    fun imageCount(zip: File, types: Set<String>): Int = ZipFile(zip).use { z ->
        z.entries().asSequence().count { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in types }
    }

    /** The name the image gets beside the zip ("Halo.zip" holding "Halo (USA).iso" -> "Halo.iso"). */
    fun target(zip: File, entryName: String): File =
        File(zip.parentFile, "${zip.nameWithoutExtension}.${entryName.substringAfterLast('.').lowercase()}")

    /**
     * Unpacks the image out of [zip]: into "<target>.part", renamed when complete, so a stopped
     * or failed unpack never leaves a broken image behind. Throws IOException with a message for
     * the user (no image inside, not enough space, stopped).
     */
    fun unpack(zip: File, types: Set<String>, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        ZipFile(zip).use { z ->
            val e = z.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in types }
                .maxByOrNull { it.size }
                ?: throw IOException("${zip.name} holds no ${types.joinToString("/") { ".$it" }} image")
            val dest = target(zip, e.name)
            val dir = dest.parentFile!!
            val need = e.size.coerceAtLeast(0)
            if (need > 0 && dir.usableSpace < need + (64L shl 20))
                throw IOException("Not enough space: the image needs ${gb(need)}, ${gb(dir.usableSpace)} is free")
            val part = File(dir, dest.name + ".part")
            try {
                z.getInputStream(e).use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            if (cancelled()) throw IOException("Stopped")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last >= (32L shl 20)) { last = done; progress(done, need) }
                        }
                        progress(done, need)
                    }
                }
                if (!part.renameTo(dest)) throw IOException("Could not rename ${part.name}")
                return dest
            } finally {
                part.delete()
            }
        }
    }

    fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${bytes shr 20} MB"
}
