package bypass.whitelist.util

/**
 * Produces a neutral, human-looking display name for the call autofill. Used
 * once to seed [Prefs.autofillName] the first time it is read, so a fresh
 * install doesn't join every call as the same hard-coded string.
 */
object NameGenerator {

    private val FIRST = listOf(
        "Alex", "Sam", "Chris", "Jordan", "Taylor", "Morgan", "Casey", "Riley",
        "Jamie", "Robin", "Drew", "Kai", "Lee", "Max", "Nico", "Quinn",
        "Sky", "Ari", "Dana", "Elliot", "Frankie", "Gray", "Harper", "Ivy",
    )

    private val LAST = listOf(
        "Miller", "Reed", "Grant", "Hayes", "Brooks", "Cole", "Dean", "Ellis",
        "Ford", "Gray", "Hunt", "Lane", "Marsh", "Nolan", "Page", "Rowe",
        "Shaw", "Tate", "Vance", "Wells", "York", "Boyd", "Chase", "Frost",
    )

    fun random(): String = "${FIRST.random()} ${LAST.random()}"
}
