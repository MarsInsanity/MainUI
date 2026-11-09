package io.github.marsinsanity.mainui.scraper

import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal ScreenScraper.fr API v2 client.
 * Docs: https://api.screenscraper.fr/webapi2.php
 */
class ScreenScraper(
    private val devId: String,
    private val devPassword: String,
    private val user: String,
    private val password: String,
) {
    class ApiException(val code: Int, message: String) : IOException(message) {
        /** Daily quota / account level problems: stop the whole run. */
        val fatal get() = code in setOf(401, 403, 423, 426, 430, 431)
        val notFound get() = code == 404
        val tooManyThreads get() = code == 429
    }

    data class UserInfo(
        val id: String, val level: Int, val maxThreads: Int, val requestsToday: Int,
        val maxRequestsPerDay: Int, val maxRequestsPerMin: Int, val maxDownloadSpeedKb: Int,
    )

    data class Media(val type: String, val region: String?, val url: String, val format: String)

    data class GameInfo(
        val id: String,
        val name: String?,
        val desc: String?,
        val releaseDate: String?,
        val developer: String?,
        val publisher: String?,
        val genre: String?,
        val players: String?,
        /** 0..1 like EmulationStation's rating. */
        val rating: Float?,
        val medias: List<Media>,
    )

    private val software = "MainUI-Android"

    private fun base(endpoint: String) = StringBuilder("https://api.screenscraper.fr/api2/$endpoint?devid=")
        .append(enc(devId)).append("&devpassword=").append(enc(devPassword))
        .append("&softname=").append(enc(software)).append("&output=json")
        .apply { if (user.isNotBlank()) append("&ssid=").append(enc(user)).append("&sspassword=").append(enc(password)) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "$software/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, body.take(200).ifBlank { "HTTP $code" })
            // The API answers some errors with HTTP 200 and plain text
            if (!body.trimStart().startsWith("{")) throw ApiException(if (body.contains("non trouv", true)) 404 else 500, body.take(200))
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    fun userInfo(): UserInfo {
        val r = get(base("ssuserInfos.php").toString()).getJSONObject("response").getJSONObject("ssuser")
        fun int(k: String, d: Int) = r.optString(k).toIntOrNull() ?: d
        return UserInfo(r.optString("id"), int("niveau", 0), int("maxthreads", 1), int("requeststoday", 0),
            int("maxrequestsperday", 20000), int("maxrequestspermin", 0), int("maxdownloadspeed", 0))
    }

    fun gameInfo(systemId: Int, file: File, hashes: Hashes?, lang: String, regions: List<String>): GameInfo? {
        val u = base("jeuInfos.php").append("&systemeid=").append(systemId)
            .append("&romtype=rom&romnom=").append(enc(file.name))
            .append("&romtaille=").append(if (file.isFile) file.length() else 0)
        if (hashes != null) u.append("&crc=").append(hashes.crc).append("&md5=").append(hashes.md5).append("&sha1=").append(hashes.sha1)
        val resp = try { get(u.toString()) } catch (e: ApiException) { if (e.notFound) return null else throw e }
        val jeu = resp.optJSONObject("response")?.optJSONObject("jeu") ?: return null
        return parse(jeu, lang, regions)
    }

    private fun parse(j: JSONObject, lang: String, regions: List<String>): GameInfo {
        fun byRegion(arr: JSONArray?): String? {
            if (arr == null || arr.length() == 0) return null
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            for (r in regions) list.firstOrNull { it.optString("region") == r }?.let { return it.optString("text") }
            return list.first().optString("text")
        }
        fun byLang(arr: JSONArray?): String? {
            if (arr == null || arr.length() == 0) return null
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            return (list.firstOrNull { it.optString("langue") == lang } ?: list.firstOrNull { it.optString("langue") == "en" } ?: list.first())
                .optString("text")
        }
        val genres = j.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { byLang(g.getJSONObject(it).optJSONArray("noms")) }.joinToString(" / ")
        }
        val medias = j.optJSONArray("medias")?.let { m ->
            (0 until m.length()).map { m.getJSONObject(it) }.map {
                Media(it.optString("type"), it.optString("region").ifEmpty { null }, it.optString("url"), it.optString("format", "png"))
            }
        } ?: emptyList()
        val note = j.optJSONObject("note")?.optString("text")?.toFloatOrNull()
        return GameInfo(
            id = j.optString("id"),
            name = byRegion(j.optJSONArray("noms")),
            desc = byLang(j.optJSONArray("synopsis")),
            releaseDate = byRegion(j.optJSONArray("dates")),
            developer = j.optJSONObject("developpeur")?.optString("text"),
            publisher = j.optJSONObject("editeur")?.optString("text"),
            genre = genres,
            players = j.optJSONObject("joueurs")?.optString("text"),
            rating = note?.let { it / 20f },
            medias = medias,
        )
    }

    /** Picks the wanted media type honouring the region preference order. */
    fun pickMedia(info: GameInfo, type: String, regions: List<String>): Media? {
        val ofType = info.medias.filter { it.type == type && it.url.isNotBlank() }
        if (ofType.isEmpty()) return null
        for (r in regions) ofType.firstOrNull { it.region == r }?.let { return it }
        return ofType.firstOrNull { it.region == null } ?: ofType.first()
    }

    fun download(media: Media, dest: File, maxWidth: Int) {
        val url = media.url + if (maxWidth > 0) "&maxwidth=$maxWidth&maxheight=$maxWidth" else ""
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 60000 }
        try {
            if (c.responseCode !in 200..299) throw ApiException(c.responseCode, "media HTTP ${c.responseCode}")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 100) { tmp.delete(); throw ApiException(404, "empty media") }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun fromSettings(): ScreenScraper {
            val s = MainUI.settings
            return ScreenScraper(
                s.ssDevId.ifBlank { BuildConfig.SS_DEV_ID },
                s.ssDevPassword.ifBlank { BuildConfig.SS_DEV_PASSWORD },
                s.ssUser, s.ssPassword,
            )
        }

        fun hasDevCredentials(): Boolean {
            val s = MainUI.settings
            return s.ssDevId.ifBlank { BuildConfig.SS_DEV_ID }.isNotBlank()
        }

        val MEDIA_TYPES = listOf(
            "box-2D" to "Box art", "box-3D" to "Box 3D", "ss" to "Screenshot", "sstitle" to "Title screen",
            "mixrbv1" to "Mix v1", "mixrbv2" to "Mix v2", "wheel" to "Logo", "marquee" to "Marquee",
        )
    }
}

data class Hashes(val crc: String, val md5: String, val sha1: String) {
    companion object {
        const val MAX_HASH_SIZE = 256L * 1024 * 1024

        fun of(f: File): Hashes? {
            if (!f.isFile || f.length() > MAX_HASH_SIZE) return null
            val crc = java.util.zip.CRC32()
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(1 shl 16)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    crc.update(buf, 0, n); md5.update(buf, 0, n); sha1.update(buf, 0, n)
                }
            }
            fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
            return Hashes("%08x".format(crc.value), hex(md5.digest()), hex(sha1.digest()))
        }
    }
}
