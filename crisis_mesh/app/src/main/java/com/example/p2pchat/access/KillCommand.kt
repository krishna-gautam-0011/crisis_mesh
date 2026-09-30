package com.example.p2pchat.access

/**
 * Parses the remote "kill" commands carried in chat messages:
 *
 *  - `killmeqwerty[User1234]`  -> [Parsed.Target]: revoke ONLY that user's device, immediately.
 *                                 (brackets optional: `killmeqwertyUser1234` also works)
 *  - `killmeqwerty`            -> [Parsed.Everyone]: every device that receives it is revoked
 *                                 after [DeviceAccess.DELAYED_REVOKE_MS] (20 minutes).
 *
 * Matching ignores case. See NearbyManager, which calls [parse] on incoming
 * messages and acts on the result via [DeviceAccess].
 */
object KillCommand {

    const val KEYWORD = "killmeqwerty"

    sealed class Parsed {
        /** Bare keyword: every receiving device is revoked after a 20-minute delay. */
        object Everyone : Parsed()
        /** Keyword + username: only the named user's device is revoked, immediately. */
        data class Target(val userId: String) : Parsed()
    }

    private val targetPattern = Regex(
        "^" + KEYWORD + "\\s*\\[?\\s*([^\\[\\]\\s]+)\\s*]?$",
        RegexOption.IGNORE_CASE
    )

    /** Returns the parsed command, or null if [text] isn't a kill command at all. */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.equals(KEYWORD, ignoreCase = true)) return Parsed.Everyone
        val id = targetPattern.matchEntire(t)?.groupValues?.get(1) ?: return null
        return Parsed.Target(id)
    }
}
