package io.github.marsinsanity.mainui.scraper

/**
 * Picks the box art for a game from the names a thumbnail set has, preferring an exact
 * identification and refusing to guess: a title match must be the same title once case,
 * punctuation and articles are ignored ("Legend of Zelda, The - A Link to the Past" =
 * "The Legend of Zelda: A Link to the Past"), never "Super Mario Bros." for
 * "Super Mario Bros. 3". Among same-title boxes the dump's own region wins, then the
 * user's region order; betas, demos, hacks and the like only match their own kind.
 */
object BoxArtMatch {
    private val tagRe = Regex("""\(([^()]*)\)""")
    private val unwanted = Regex("""^(Beta|Proto|Prototype|Demo|Sample|Kiosk|Promo|Pirate|Hack|Unl|Aftermarket|Alt|Bootleg|Debug|Program|Test|Possible Proto)\b""", RegexOption.IGNORE_CASE)
    private val trailingArticle = Regex(""", (The|A|An)(?=( - |: |$| \())""", RegexOption.IGNORE_CASE)
    private val leadingArticle = Regex("""^(The|A|An) """, RegexOption.IGNORE_CASE)
    private val disc = Regex("""^Disc \d+""", RegexOption.IGNORE_CASE)

    val REGIONS = setOf("USA", "Europe", "Japan", "World", "Asia", "Korea", "China", "Taiwan", "Hong Kong", "Brazil",
        "Australia", "Canada", "France", "Germany", "Spain", "Italy", "Netherlands", "Sweden", "UK", "Russia",
        "Scandinavia", "Denmark", "Finland", "Norway", "Portugal", "Greece", "Poland", "Mexico", "Latin America")

    /** libretro thumbnail file name for a game name: the characters & * / : ` < > ? \ | become _. */
    fun thumbName(name: String) = name.replace(Regex("""[&*/:`<>?\\|]"""), "_")

    fun tags(name: String): List<String> = tagRe.findAll(name).map { it.groupValues[1].trim() }.toList()

    fun regions(name: String): Set<String> = tags(name).flatMap { t -> t.split(',').map { it.trim() } }.filter { it in REGIONS }.toSet()

    /** The title alone, compared without case, punctuation or a leading / trailing article. */
    fun title(name: String): String {
        var t = name.substringBefore(" (").substringBefore(" [").trim()
        t = trailingArticle.replace(t, "")
        t = leadingArticle.replace(t, "")
        // Accents don't count ("Pokémon" = "Pokemon").
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.lowercase().replace("&", " and ").replace("'", "")
        return Regex("[^a-z0-9]+").replace(t, " ").trim()
    }

    /** Thumbnail regions in order of preference, from ScreenScraper style codes ("us,wor,eu,ss,jp"). */
    fun regionPrefs(ssRegions: String): List<String> = ssRegions.split(',').mapNotNull {
        when (it.trim()) {
            "us" -> "USA"; "wor" -> "World"; "eu" -> "Europe"; "jp" -> "Japan"; "fr" -> "France"; "de" -> "Germany"
            "es" -> "Spain"; "it" -> "Italy"; "uk" -> "UK"; "au" -> "Australia"; "kr" -> "Korea"; "br" -> "Brazil"
            "cn" -> "China"; "tw" -> "Taiwan"; "asi" -> "Asia"; "nl" -> "Netherlands"; "se" -> "Sweden"; else -> null
        }
    }.distinct()

    /**
     * The thumbnail (name without .png) for a game.
     * [identified]: canonical names the dump's CRC / serial / file name gave (exact).
     * [fileName]: the rom's name without extension, used when nothing identified it.
     * [available]: the thumbnail set's names (without .png), null when the list couldn't be read.
     * Returns null when no box is the same game.
     */
    fun pick(identified: List<String>, fileName: String, available: Set<String>?, prefs: List<String>): String? {
        if (available == null) return identified.firstOrNull()?.let(::thumbName)
        for (n in identified) thumbName(n).let { if (it in available) return it }
        // Same title: from the identified name (its tags say the region) or else the file name.
        val source = identified.firstOrNull() ?: fileName
        thumbName(source).let { if (it in available) return it }
        val t = title(source)
        if (t.isEmpty()) return null
        val sameTitle = available.filter { title(it) == t }
        if (sameTitle.isEmpty()) return null
        return sameTitle.maxWithOrNull(compareBy<String> { score(it, source, prefs) }.thenBy { -it.length }.thenByDescending { it })
            ?.takeIf { score(it, source, prefs) > -100 }
    }

    fun score(candidate: String, source: String, prefs: List<String>): Int {
        val cTags = tags(candidate); val sTags = tags(source)
        val cRegions = regions(candidate); val sRegions = regions(source)
        var s = 0
        if (sRegions.isNotEmpty() && cRegions.any { it in sRegions }) s += 100
        val pref = prefs.indexOfFirst { it in cRegions }
        if (pref >= 0) s += (prefs.size - pref) * 10
        for (t in cTags) {
            if (unwanted.containsMatchIn(t) && sTags.none { it.equals(t, true) }) s -= 1000   // never a beta / demo / hack box for another dump
            if (disc.containsMatchIn(t)) s += if (sTags.any { it.equals(t, true) }) 50 else -30
            else if (t !in REGIONS && t.split(',').none { it.trim() in REGIONS } && sTags.any { it.equals(t, true) }) s += 5
        }
        return s - (cTags.size - sTags.size).coerceAtLeast(0)
    }
}
