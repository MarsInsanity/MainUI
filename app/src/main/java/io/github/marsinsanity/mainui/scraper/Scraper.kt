package io.github.marsinsanity.mainui.scraper

import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.GameListXml
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.LogiqxDat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Live progress shared with the UI (read from the render thread). */
object ScrapeState {
    @Volatile var running = false
    @Volatile var total = 0
    val done = AtomicInteger()
    val found = AtomicInteger()
    val missing = AtomicInteger()
    val skipped = AtomicInteger()
    val errors = AtomicInteger()
    @Volatile var threads = 0
    @Volatile var current = ""
    @Volatile var message = ""
    @Volatile var quota = ""
    @Volatile var log: List<String> = emptyList()

    fun reset() {
        total = 0; done.set(0); found.set(0); missing.set(0); skipped.set(0); errors.set(0)
        threads = 0; current = ""; message = ""; quota = ""; log = emptyList()
    }

    fun addLog(s: String) { log = (listOf(s) + log).take(6) }
}

/**
 * Scrapes artwork + metadata from ScreenScraper using as many worker threads
 * as the user's ScreenScraper account allows (maxthreads grows with the
 * donation level). Images go to <volume>/roms/<system>/images/ on the SD card
 * or on internal storage; metadata goes to gamelist.xml (dArkOS / ES format),
 * a Logiqx DAT, or both.
 */
object Scraper {
    private const val TAG = "MainUI.Scraper"
    private val cancel = AtomicBoolean(false)

    fun cancel() { cancel.set(true); ScrapeState.message = "Stopping..." }

    private fun regions() = MainUI.settings.ssRegions.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Blocking. Call from [io.github.marsinsanity.mainui.service.ScraperService]. */
    fun run(systemIds: List<String>?) {
        val st = ScrapeState
        st.reset()
        st.running = true
        cancel.set(false)
        try {
            if (!ScreenScraper.hasDevCredentials()) {
                st.message = "ScreenScraper developer ID missing (Scraper > Developer ID)"; return
            }
            val api = ScreenScraper.fromSettings()
            st.message = "Connecting to ScreenScraper..."
            var threads = 1
            var perMin = 0
            try {
                val u = api.userInfo()
                threads = u.maxThreads.coerceAtLeast(1)
                perMin = u.maxRequestsPerMin
                st.quota = "${u.requestsToday}/${u.maxRequestsPerDay} requests today"
            } catch (e: ScreenScraper.ApiException) {
                if (e.code == 403 || e.code == 401) { st.message = "Login failed: ${e.message}"; return }
                Log.w(TAG, "userInfo failed", e)
            }
            val override = MainUI.settings.ssThreadsOverride
            if (override in 1 until threads) threads = override
            st.threads = threads

            st.message = "Collecting games..."
            val systems = MainUI.games.systems(MainUI.settings.showUnknownFolders, refresh = true)
                .filter { systemIds == null || it.id in systemIds }
                .filter { it.def.screenscraperId != null }
            val jobs = ArrayList<Pair<GameSystem, Game>>()
            for (s in systems) collect(s, null, 0, jobs)
            st.total = jobs.size
            if (jobs.isEmpty()) { st.message = "Nothing to scrape"; return }

            val queue = LinkedBlockingQueue(jobs)
            val limiter = RateLimiter(perMin)
            val gamelists = ConcurrentHashMap<String, GameListXml>()
            val dats = ConcurrentHashMap<String, LogiqxDat>()
            val pool = Executors.newFixedThreadPool(threads)
            val fatal = AtomicBoolean(false)
            st.message = "Scraping with $threads thread${if (threads > 1) "s" else ""}"
            repeat(threads) {
                pool.execute {
                    while (!cancel.get() && !fatal.get()) {
                        val (sys, game) = queue.poll() ?: break
                        st.current = "${sys.def.short}: ${game.name}"
                        try {
                            when (scrapeGame(api, sys, game, gamelists, dats, limiter)) {
                                Result.FOUND -> st.found.incrementAndGet()
                                Result.NOT_FOUND -> { st.missing.incrementAndGet(); st.addLog("Not found: ${game.name}") }
                                Result.SKIPPED -> st.skipped.incrementAndGet()
                            }
                        } catch (e: ScreenScraper.ApiException) {
                            if (e.fatal) { fatal.set(true); st.message = "Stopped: ${describe(e)}" }
                            else if (e.tooManyThreads) { queue.put(sys to game); Thread.sleep(2000); continue }
                            else { st.errors.incrementAndGet(); st.addLog("Error ${e.code}: ${game.name}") }
                        } catch (e: Exception) {
                            st.errors.incrementAndGet(); st.addLog("Error: ${game.name}: ${e.message}")
                            Log.w(TAG, "scrape failed ${game.file}", e)
                        }
                        st.done.incrementAndGet()
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
            gamelists.values.forEach { gl -> synchronized(gl) { runCatching { gl.save() } } }
            dats.values.forEach { d -> synchronized(d) { runCatching { d.save() } } }
            MainUI.games.invalidate()
            if (!fatal.get()) st.message = if (cancel.get()) "Cancelled" else "Done"
        } catch (e: Exception) {
            Log.e(TAG, "scraper crashed", e)
            st.message = "Error: ${e.message}"
        } finally {
            st.current = ""
            st.running = false
        }
    }

    private fun describe(e: ScreenScraper.ApiException) = when (e.code) {
        401 -> "API closed for non members right now"
        403 -> "wrong developer or user credentials"
        423 -> "ScreenScraper API is closed"
        426 -> "software not allowed by ScreenScraper"
        430 -> "daily quota reached"
        431 -> "too many unknown roms today"
        else -> e.message ?: "HTTP ${e.code}"
    }

    private fun collect(sys: GameSystem, dir: File?, depth: Int, out: MutableList<Pair<GameSystem, Game>>) {
        for (g in MainUI.games.list(sys, dir)) {
            if (g.isFolder) { if (depth < 3) collect(sys, g.file, depth + 1, out) }
            else if (!MainUI.settings.ssOnlyMissing || MainUI.games.imageFor(sys, g) == null) out += sys to g
            else ScrapeState.skipped.incrementAndGet()
        }
    }

    enum class Result { FOUND, NOT_FOUND, SKIPPED }

    private fun scrapeGame(api: ScreenScraper, sys: GameSystem, game: Game, gamelists: ConcurrentHashMap<String, GameListXml>,
                           dats: ConcurrentHashMap<String, LogiqxDat>, limiter: RateLimiter): Result {
        val file = game.file ?: return Result.SKIPPED
        val ssId = sys.def.screenscraperId ?: return Result.SKIPPED
        val hashes = runCatching { Hashes.of(file) }.getOrNull()
        limiter.acquire()
        val info = api.gameInfo(ssId, file, hashes, MainUI.settings.ssLanguage, regions()) ?: return Result.NOT_FOUND
        val media = api.pickMedia(info, MainUI.settings.ssMedia, regions())
            ?: api.pickMedia(info, "box-2D", regions()) ?: api.pickMedia(info, "ss", regions())
        val sysRoot = MainUI.games.systemRootFor(sys, file.parentFile ?: file)
        var imageRel: String? = null
        if (media != null) {
            val romRoot = sysRoot.parentFile ?: sysRoot
            val imgRoot = File(MainUI.storage.imageRootFor(romRoot), sysRoot.name)
            val relDir = runCatching { file.parentFile!!.relativeTo(sysRoot).path }.getOrDefault("")
            val ext = if (media.format.equals("jpg", true) || media.format.equals("jpeg", true)) "jpg" else "png"
            val dest = File(File(File(imgRoot, "images"), relDir), "${file.nameWithoutExtension}.$ext")
            api.download(media, dest, MainUI.settings.ssMaxImageWidth)
            imageRel = if (dest.absolutePath.startsWith(sysRoot.absolutePath + "/")) "./" + dest.relativeTo(sysRoot).path else dest.absolutePath
        }
        val format = MainUI.settings.ssMetaFormat
        if (format == "logiqx" || format == "both") {
            val d = dats.getOrPut(sysRoot.absolutePath) { LogiqxDat.forWriting(sysRoot) }
            synchronized(d) {
                d.put(file, info.name, info.desc, info.releaseDate, info.publisher ?: info.developer, hashes)
                if (ScrapeState.done.get() % 25 == 0) runCatching { d.save() }
            }
        }
        if (format == "gamelist" || format == "both") {
            val gl = gamelists.getOrPut(sysRoot.absolutePath) { GameListXml(sysRoot).load() }
            synchronized(gl) {
                gl.put(file) { e ->
                    info.name?.let { e.fields["name"] = it }
                    info.desc?.let { e.fields["desc"] = it }
                    imageRel?.let { e.fields["image"] = it }
                    info.rating?.let { e.fields["rating"] = "%.2f".format(java.util.Locale.US, it) }
                    info.releaseDate?.let { d -> e.fields["releasedate"] = toEsDate(d) }
                    info.developer?.let { e.fields["developer"] = it }
                    info.publisher?.let { e.fields["publisher"] = it }
                    info.genre?.let { e.fields["genre"] = it }
                    info.players?.let { e.fields["players"] = it }
                    e.attrs["source"] = "ScreenScraper.fr"; e.attrs["id"] = info.id
                }
                if (ScrapeState.done.get() % 25 == 0) runCatching { gl.save() }
            }
        }
        return if (media != null || info.name != null) Result.FOUND else Result.NOT_FOUND
    }

    /** "1991-07-23" / "1991" -> "19910723T000000" (EmulationStation format). */
    private fun toEsDate(d: String): String {
        val digits = d.filter { it.isDigit() }
        return when (digits.length) {
            8 -> "${digits}T000000"
            6 -> "${digits}01T000000"
            4 -> "${digits}0101T000000"
            else -> d
        }
    }

    /** Scrapes one game synchronously (game options menu). Returns a message for the UI. */
    fun scrapeOne(game: Game): String {
        if (!ScreenScraper.hasDevCredentials()) return "Set a ScreenScraper developer ID first"
        val sys = io.github.marsinsanity.mainui.ui.scenes.Launch.gameSystem(game.systemId) ?: return "Unknown system"
        if (sys.def.screenscraperId == null) return "${sys.name} is not on ScreenScraper"
        val gamelists = ConcurrentHashMap<String, GameListXml>()
        val dats = ConcurrentHashMap<String, LogiqxDat>()
        return try {
            val r = scrapeGame(ScreenScraper.fromSettings(), sys, game, gamelists, dats, RateLimiter(0))
            gamelists.values.forEach { it.save() }
            dats.values.forEach { it.save() }
            MainUI.games.invalidate()
            if (r == Result.FOUND) "Artwork updated" else "Game not found on ScreenScraper"
        } catch (e: ScreenScraper.ApiException) {
            "ScreenScraper: ${describe(e)}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Spaces requests to honour maxrequestspermin. */
    class RateLimiter(perMinute: Int) {
        private val intervalMs = if (perMinute > 0) 60_000L / perMinute else 0L
        private var next = 0L

        fun acquire() {
            if (intervalMs == 0L) return
            val wait: Long
            synchronized(this) {
                val now = System.currentTimeMillis()
                val slot = maxOf(now, next)
                next = slot + intervalMs
                wait = slot - now
            }
            if (wait > 0) Thread.sleep(wait)
        }
    }
}
