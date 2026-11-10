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
 * donation level), and box art from GameTDB / libretro thumbnails ([FreeArt])
 * where ScreenScraper has none or isn't set up. Images go to <volume>/roms/<system>/images/ on the SD card
 * or on internal storage; metadata goes to gamelist.xml (dArkOS / ES format),
 * a Logiqx DAT, or both.
 */
object Scraper {
    private const val TAG = "MainUI.Scraper"
    private val cancel = AtomicBoolean(false)
    /**
     * ScreenScraper unreachable (no network, server down) several times in a row: skip it for a
     * minute instead of waiting for its timeouts game after game, then try again. One per run
     * (and one per single-game scrape), so an outage never outlives the scrape it happened in.
     */
    class SsBreaker(private val log: (String) -> Unit = {}, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
        private var failures = 0
        private var downUntil = 0L
        /** False while it is resting after [LIMIT] failures in a row. */
        @Synchronized fun allow(): Boolean = failures < LIMIT || clock() >= downUntil
        @Synchronized fun success() { failures = 0; downUntil = 0L }
        @Synchronized fun failure() {
            failures++
            if (failures >= LIMIT) {
                if (failures == LIMIT) log("ScreenScraper unreachable $LIMIT times in a row: skipping it for a minute at a time")
                downUntil = clock() + REST_MS
            }
        }
        companion object { const val LIMIT = 5; const val REST_MS = 60_000L }
    }

    fun cancel() { if (ScrapeState.running) { cancel.set(true); ScrapeState.message = "Stopping..." } }

    /** A stop was asked for and the run hasn't ended yet. */
    val stopping: Boolean get() = cancel.get() && ScrapeState.running

    private fun regions() = MainUI.settings.ssRegions.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private var logOut: java.io.Writer? = null

    /** The run's log, <MainUI>/logs/scraper.txt: every step and every game's outcome. */
    private fun fileLog(s: String) {
        synchronized(this) {
            runCatching {
                logOut?.apply { write(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s + "\n"); flush() }
            }
        }
    }

    /** Blocking. Call from [io.github.marsinsanity.mainui.service.ScraperService]. */
    fun run(systemIds: List<String>?) {
        val st = ScrapeState
        st.reset()
        st.running = true
        cancel.set(false)
        logOut = runCatching { File(MainUI.storage.dataDir("logs"), "scraper.txt").bufferedWriter() }.getOrNull()
        fileLog("MainUI ${io.github.marsinsanity.mainui.BuildConfig.VERSION_NAME}, sources ${MainUI.settings.artSources}, " +
            "ScreenScraper developer ID ${if (ScreenScraper.hasDevCredentials()) "set" else "not set"}, " +
            "user ${MainUI.settings.ssUser.ifBlank { "anonymous" }}, only missing art ${MainUI.settings.ssOnlyMissing}, systems ${systemIds ?: "all"}")
        try {
            val sources = MainUI.settings.artSources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val free = sources.filter { it != "ss" }
            val useSs = "ss" in sources && ScreenScraper.hasDevCredentials()
            if (!useSs && free.isEmpty()) {
                st.message = "ScreenScraper developer ID missing (Scraper > Developer ID)"; return
            }
            var api = if (useSs) ScreenScraper.fromSettings() else null
            if (cancel.get()) { st.message = "Cancelled"; return }
            val freeArt = FreeArt.Session(free, BoxArtMatch.regionPrefs(MainUI.settings.ssRegions))
            var threads = if (useSs) 1 else 4
            var perMin = 0
            // ScreenScraper left out for this run (an account-level error with free sources on), and why.
            val ssOffReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
            if (api != null) {
                st.message = "Connecting to ScreenScraper..."
                try {
                    val u = api.userInfo()
                    threads = u.maxThreads.coerceAtLeast(1)
                    perMin = u.maxRequestsPerMin
                    st.quota = "${u.requestsToday}/${u.maxRequestsPerDay} requests today"
                    fileLog("ScreenScraper: level ${u.level}, ${u.maxThreads} threads, ${st.quota}, $perMin per minute")
                } catch (e: ScreenScraper.ApiException) {
                    fileLog("ScreenScraper user info failed: ${e.code} ${e.message}")
                    if (e.code == 403 || e.code == 401) {
                        if (free.isEmpty()) { st.message = "Login failed: ${e.message}"; return }
                        // (401 is also "API closed for non-members" when it is busy.) The free sources still run.
                        fileLog("ScreenScraper off for this run"); st.addLog("ScreenScraper: ${e.message?.take(60)}: free sources only")
                        ssOffReason.set(e.message?.take(60) ?: "login failed")
                        api = null
                    }
                    Log.w(TAG, "userInfo failed", e)
                } catch (e: java.io.IOException) {
                    fileLog("ScreenScraper unreachable: ${e.message}")
                    Log.w(TAG, "userInfo failed", e)
                }
            }
            if (useSs && api == null) threads = 4 // ScreenScraper left out: the free sources' threads
            val override = MainUI.settings.ssThreadsOverride
            if (override in 1 until threads || (api == null && override > 0)) threads = override
            st.threads = threads

            st.message = "Collecting games..."
            val systems = MainUI.games.systems(MainUI.settings.showUnknownFolders, refresh = true)
                .filter { systemIds == null || it.id in systemIds }
                // (Systems only ScreenScraper covers stay when it was turned off: their games are
                // reported as not scraped because of that, not as "nothing to scrape".)
                .filter { (useSs && it.def.screenscraperId != null) || FreeArt.supports(free, it.id) }
            val jobs = ArrayList<Pair<GameSystem, Game>>()
            for (s in systems) {
                if (cancel.get()) break
                st.message = "Collecting games: ${s.def.short}..."
                collect(s, null, 0, jobs)
            }
            if (cancel.get()) { st.message = "Cancelled"; return }
            fileLog("${jobs.size} games to scrape in ${systems.size} systems (${systems.joinToString { it.id }}), ${st.skipped.get()} already have art")
            // LaunchBox matches by title against its whole database: fetch / refresh it first.
            if (FreeArt.LAUNCHBOX in free && jobs.any { LaunchBoxDb.supports(it.first.id) }) {
                if (!FreeArt.ensureLaunchBox({ st.message = it }, { cancel.get() })) { st.addLog("LaunchBox database unavailable"); fileLog("LaunchBox database unavailable") }
                if (cancel.get()) { st.message = "Cancelled"; return }
            }
            if (FreeArt.THEGAMESDB in free && jobs.any { TheGamesDb.supports(it.first.id) }) {
                if (!FreeArt.ensureTheGamesDb({ st.message = it }, { cancel.get() })) { st.addLog("TheGamesDB unavailable"); fileLog("TheGamesDB unavailable") }
                if (cancel.get()) { st.message = "Cancelled"; return }
            }
            st.total = jobs.size
            if (jobs.isEmpty()) { st.message = "Nothing to scrape"; return }

            val queue = LinkedBlockingQueue(jobs)
            val limiter = RateLimiter(perMin)
            val breaker = SsBreaker(log = { msg -> fileLog(msg); st.addLog(msg) })
            val gamelists = ConcurrentHashMap<String, GameListXml>()
            val dats = ConcurrentHashMap<String, LogiqxDat>()
            val pool = Executors.newFixedThreadPool(threads)
            val fatal = AtomicBoolean(false)
            // An account-level ScreenScraper error (quota, closed to non-members) with free sources
            // on: ScreenScraper is left out for the rest of the run, the free sources go on.
            val ssOff = AtomicBoolean(ssOffReason.get() != null)
            val ssApi = api
            st.message = "Scraping with $threads thread${if (threads > 1) "s" else ""}" + if (api == null) " (no ScreenScraper)" else ""
            repeat(threads) {
                pool.execute {
                    while (!cancel.get() && !fatal.get()) {
                        val (sys, game) = queue.poll() ?: break
                        st.current = "${sys.def.short}: ${game.name}"
                        if ((ssOff.get() || ssApi == null) && useSs && !FreeArt.supports(free, sys.id)) {
                            // Only ScreenScraper has this system, and it is off: an error with its reason.
                            fileLog("${sys.id}: ${game.file?.name}: ScreenScraper off (${ssOffReason.get()})")
                            st.errors.incrementAndGet(); st.done.incrementAndGet()
                            continue
                        }
                        try {
                            val r = scrapeGame(if (ssOff.get()) null else ssApi, freeArt, sys, game, gamelists, dats, limiter, breaker)
                            when (r) {
                                Result.FOUND -> st.found.incrementAndGet()
                                Result.NOT_FOUND -> { st.missing.incrementAndGet(); st.addLog("Not found: ${game.name}") }
                                Result.SKIPPED -> st.skipped.incrementAndGet()
                            }
                            fileLog("${sys.id}: ${game.file?.name}: $r")
                        } catch (e: ScreenScraper.ApiException) {
                            fileLog("${sys.id}: ${game.file?.name}: ScreenScraper ${e.code} ${e.message}")
                            if (e.fatal && free.isNotEmpty()) {
                                if (ssOff.compareAndSet(false, true)) {
                                    ssOffReason.set(describe(e))
                                    fileLog("ScreenScraper off: ${describe(e)}"); st.addLog("ScreenScraper off for this run: ${describe(e)}")
                                }
                                queue.put(sys to game); continue
                            }
                            else if (e.fatal) { fatal.set(true); st.message = "Stopped: ${describe(e)}" }
                            else if (e.tooManyThreads) { queue.put(sys to game); Thread.sleep(2000); continue }
                            else { st.errors.incrementAndGet(); st.addLog("ScreenScraper ${e.code}: ${game.name}: ${e.message?.take(60)}") }
                        } catch (e: Exception) {
                            fileLog("${sys.id}: ${game.file?.name}: error $e")
                            st.errors.incrementAndGet(); st.addLog("Error: ${game.name}: ${e.message}")
                            Log.w(TAG, "scrape failed ${game.file}", e)
                        }
                        st.done.incrementAndGet()
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
            var unsaved = 0
            gamelists.values.forEach { gl -> synchronized(gl) { runCatching { gl.save() }.onFailure { unsaved++; fileLog("not saved: ${it.message}") } } }
            dats.values.forEach { d -> synchronized(d) { runCatching { d.save() }.onFailure { unsaved++; fileLog("not saved: ${it.message}") } } }
            if (unsaved > 0) st.addLog("$unsaved metadata file(s) could not be read, so they were left as they were (see logs/scraper.txt)")
            MainUI.games.invalidate()
            // Games looked at before (no art then) would otherwise stay without it until a restart.
            io.github.marsinsanity.mainui.ui.Images.clear()
            if (!fatal.get()) st.message = when {
                cancel.get() -> "Cancelled"
                st.found.get() == 0 && st.errors.get() > 0 -> "Done, but every game failed: see the log below"
                st.found.get() == 0 -> "Done: no box art found (check Box art sources)"
                else -> "Done"
            } + (ssOffReason.get()?.let { " (ScreenScraper off: $it)" } ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "scraper crashed", e)
            fileLog("crashed: ${Log.getStackTraceString(e)}")
            st.message = "Error: ${e.message}"
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "scraper out of memory", e)
            fileLog("out of memory: $e")
            st.message = "Error: out of memory"
        } finally {
            fileLog("end: ${st.message} (found ${st.found.get()}, not found ${st.missing.get()}, errors ${st.errors.get()}, skipped ${st.skipped.get()})")
            synchronized(this) { runCatching { logOut?.close() }; logOut = null }
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

    /** Adds the games to scrape; the progress line counts them as it goes, so it never looks stuck. */
    private fun collect(sys: GameSystem, dir: File?, depth: Int, out: MutableList<Pair<GameSystem, Game>>) {
        val st = ScrapeState
        for (g in MainUI.games.list(sys, dir)) {
            if (cancel.get()) return
            if (g.isFolder) { if (depth < 3) collect(sys, g.file, depth + 1, out); continue }
            if (!MainUI.settings.ssOnlyMissing || MainUI.games.imageFor(sys, g) == null) out += sys to g
            else st.skipped.incrementAndGet()
            val n = out.size + st.skipped.get()
            if (n % 10 == 0) st.message = "Collecting games: ${sys.def.short}... $n found" +
                if (st.skipped.get() > 0) " (${st.skipped.get()} already have art)" else ""
        }
    }

    enum class Result { FOUND, NOT_FOUND, SKIPPED }

    private fun scrapeGame(api: ScreenScraper?, freeArt: FreeArt.Session, sys: GameSystem, game: Game,
                           gamelists: ConcurrentHashMap<String, GameListXml>, dats: ConcurrentHashMap<String, LogiqxDat>,
                           limiter: RateLimiter, breaker: SsBreaker): Result {
        val file = game.file ?: return Result.SKIPPED
        val ssId = sys.def.screenscraperId
        val hashes = if (api != null && ssId != null) runCatching { Hashes.of(file) }.getOrNull() else null
        var info: ScreenScraper.GameInfo? = null
        var imageRel: String? = null
        var source = "ScreenScraper.fr"
        val sysRoot = MainUI.games.systemRootFor(sys, file.parentFile ?: file)
        fun dest(ext: String): File {
            val romRoot = sysRoot.parentFile ?: sysRoot
            val imgRoot = File(MainUI.storage.imageRootFor(romRoot), sysRoot.name)
            val relDir = runCatching { file.parentFile!!.relativeTo(sysRoot).path }.getOrDefault("")
            return File(File(File(imgRoot, "images"), relDir), "${file.nameWithoutExtension}.$ext")
        }
        fun rel(f: File) = if (f.absolutePath.startsWith(sysRoot.absolutePath + "/")) "./" + f.relativeTo(sysRoot).path else f.absolutePath

        // A ScreenScraper problem with this game (server down, error page, media download failed)
        // doesn't end it: the free sources still get their turn, and what SS did send is kept.
        // Only account-level errors (quota, login) and "too many threads" go up to the run.
        var ssError: Exception? = null
        var mediaError: Exception? = null
        fun ssFailed(e: Exception) {
            if (e is ScreenScraper.ApiException && (e.fatal || e.tooManyThreads)) throw e
            if (e !is ScreenScraper.ApiException) breaker.failure() // the connection, not this game
            ssError = e
        }
        if (api != null && ssId != null && !breaker.allow()) {
            // Resting after repeated connection failures: this game counts as an error (if nothing
            // else finds it), never as "not found".
            ssError = java.io.IOException("ScreenScraper unreachable (skipped for now)")
        } else if (api != null && ssId != null) {
            limiter.acquire()
            info = try {
                api.gameInfo(ssId, file, hashes, MainUI.settings.ssLanguage, regions()).also { breaker.success() }
            } catch (e: java.io.IOException) { ssFailed(e); null }
            val media = info?.let {
                api.pickMedia(it, MainUI.settings.ssMedia, regions()) ?: api.pickMedia(it, "box-2D", regions()) ?: api.pickMedia(it, "ss", regions())
            }
            if (media != null) {
                val ext = if (media.format.equals("jpg", true) || media.format.equals("jpeg", true)) "jpg" else "png"
                val d = dest(ext)
                try {
                    api.download(media, d, MainUI.settings.ssMaxImageWidth)
                    imageRel = rel(d)
                } catch (e: java.io.IOException) { ssFailed(e); mediaError = e }
            }
        }
        // Box art from the free sources when ScreenScraper had none (or isn't used).
        // (A free source's failure doesn't throw away what ScreenScraper sent: it is kept below.)
        var freeError: Exception? = null
        if (imageRel == null && freeArt.sources.isNotEmpty()) {
            val d = dest("png")
            try {
                FreeArt.find(freeArt, sys.id, file, d, MainUI.settings.ssMaxImageWidth, hashes?.crc)?.let { imageRel = rel(d); source = it }
            } catch (e: Exception) { freeError = e }
        }
        val format = MainUI.settings.ssMetaFormat
        if (imageRel == null) {
            // Nothing found anywhere: report why SS (or a free source) failed (an error, not "not found").
            if (info == null) { (ssError ?: freeError)?.let { throw it }; return Result.NOT_FOUND }
            // SS knew the game but its image failed: with nothing else to save that is a failure too;
            // with metadata to save, keep that and say the image failed.
            (mediaError ?: freeError)?.let { e ->
                if (format == "none") throw e
                fileLog("${sys.id}: ${file.name}: metadata saved, image download failed: $e")
                ScrapeState.addLog("Image failed: ${game.name}: ${e.message?.take(50)}")
            }
        }
        if (info != null && (format == "logiqx" || format == "both")) {
            val d = dats.getOrPut(sysRoot.absolutePath) { LogiqxDat.forWriting(sysRoot) }
            synchronized(d) {
                d.put(file, info.name, info.desc, info.releaseDate, info.publisher ?: info.developer, hashes)
                if (ScrapeState.done.get() % 25 == 0) runCatching { d.save() }
            }
        }
        // The image path always goes to gamelist.xml (that is where it is read from), metadata when wanted.
        val meta = info != null && (format == "gamelist" || format == "both")
        if (imageRel != null || meta) {
            val gl = gamelists.getOrPut(sysRoot.absolutePath) { GameListXml(sysRoot).load() }
            synchronized(gl) {
                gl.put(file) { e ->
                    if (meta) {
                        info!!.name?.let { e.fields["name"] = it }
                        info.desc?.let { e.fields["desc"] = it }
                        info.rating?.let { e.fields["rating"] = "%.2f".format(java.util.Locale.US, it) }
                        info.releaseDate?.let { d -> e.fields["releasedate"] = toEsDate(d) }
                        info.developer?.let { e.fields["developer"] = it }
                        info.publisher?.let { e.fields["publisher"] = it }
                        info.genre?.let { e.fields["genre"] = it }
                        info.players?.let { e.fields["players"] = it }
                        e.attrs["id"] = info.id
                    }
                    imageRel?.let { e.fields["image"] = it }
                    e.attrs["source"] = source
                }
                if (ScrapeState.done.get() % 25 == 0) runCatching { gl.save() }
            }
        }
        // (Metadata only counts when some is written: not with "Images only".)
        return if (imageRel != null || (info?.name != null && format != "none")) Result.FOUND else Result.NOT_FOUND
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
        // A full run keeps its own copies of the gamelists and would write over this game's entry.
        if (ScrapeState.running) return "The scraper is running: this game is scraped with the others"
        val sys = io.github.marsinsanity.mainui.ui.scenes.Launch.gameSystem(game.systemId) ?: return "Unknown system"
        val sources = MainUI.settings.artSources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val free = sources.filter { it != "ss" }
        val api = if ("ss" in sources && ScreenScraper.hasDevCredentials() && sys.def.screenscraperId != null) ScreenScraper.fromSettings() else null
        if (api == null && !FreeArt.supports(free, sys.id)) {
            return if (!ScreenScraper.hasDevCredentials()) "Set a ScreenScraper developer ID first, or turn on the free box art sources"
            else "No box art source covers ${sys.name}"
        }
        val gamelists = ConcurrentHashMap<String, GameListXml>()
        val dats = ConcurrentHashMap<String, LogiqxDat>()
        return try {
            val r = scrapeGame(api, FreeArt.Session(free, BoxArtMatch.regionPrefs(MainUI.settings.ssRegions)), sys, game, gamelists, dats,
                RateLimiter(0), SsBreaker())
            // Each file on its own: one that can't be written (a gamelist.xml MainUI couldn't read)
            // doesn't stop the others, and the art already downloaded still counts.
            val unsaved = (gamelists.values.map { gl -> runCatching { gl.save() } } + dats.values.map { d -> runCatching { d.save() } })
                .mapNotNull { it.exceptionOrNull() }
            MainUI.games.invalidate()
            io.github.marsinsanity.mainui.ui.Images.clear()
            // LaunchBox / TheGamesDB only come with a full run (big downloads): said when they could have helped.
            val notYet = listOfNotNull(
                "LaunchBox".takeIf { FreeArt.LAUNCHBOX in free && LaunchBoxDb.supports(sys.id) && !FreeArt.hasLaunchBoxIndex() },
                "TheGamesDB".takeIf { FreeArt.THEGAMESDB in free && TheGamesDb.supports(sys.id) && !FreeArt.hasTgdbIndex() },
            )
            val note = (if (r != Result.FOUND && notYet.isNotEmpty()) " (${notYet.joinToString(" / ")} not downloaded yet: scrape the system once)" else "") +
                if (unsaved.isEmpty()) "" else " (${unsaved.size} metadata file${if (unsaved.size > 1) "s" else ""} left unchanged: couldn't be read)"
            if (r == Result.FOUND) "Artwork updated$note" else "No box art found for this game$note"
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
