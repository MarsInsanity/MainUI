package io.github.marsinsanity.mainui.core

/**
 * Every match's groups (0 = the whole match) using one java.util.regex.Matcher for the whole
 * input. Kotlin's findAll makes a new Matcher for each match, and on Android every Matcher copies
 * the whole input into ICU: on a 2 MB thumbnail list with thousands of links that took minutes.
 */
fun Regex.allGroups(input: CharSequence): List<List<String>> {
    val m = toPattern().matcher(input)
    val out = ArrayList<List<String>>()
    while (m.find()) out += (0..m.groupCount()).map { m.group(it) ?: "" }
    return out
}
