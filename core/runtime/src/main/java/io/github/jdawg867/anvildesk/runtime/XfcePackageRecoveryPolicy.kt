package io.github.jdawg867.anvildesk.runtime

/**
 * Read-only policy for the known M7 tzdata -> Python -> XFCE configuration cascade.
 *
 * This does not invoke dpkg. A caller must deliberately authorize and execute a
 * bounded recovery operation after inspecting the returned state.
 */
object XfcePackageRecoveryPolicy {
    const val DPKG_STATUS_RELATIVE_PATH = "var/lib/dpkg/status"

    val CONFIGURE_ORDER: List<String> = listOf(
        "tzdata",
        "libpython3.12-stdlib",
        "python3.12",
        "libpython3-stdlib",
        "python3",
        "python3-urllib3",
        "xfce4-helpers",
        "xfce4-settings",
        "xfce4-session",
    )

    private val allowedIncompleteStates = setOf(
        "install ok unpacked",
        "install ok half-configured",
    )

    data class Assessment(
        val packagesToConfigure: List<String>,
        val blockers: List<String>,
    ) {
        val safeToOfferRecovery: Boolean get() =
            blockers.isEmpty() && packagesToConfigure.isNotEmpty()
    }

    /** Treat malformed, missing, or unexpected states as blockers: never guess. */
    fun assess(statusText: String): Assessment {
        val statuses = mutableMapOf<String, String>()
        val blockers = mutableListOf<String>()

        for (paragraph in statusText.split(Regex("\\n\\s*\\n"))) {
            val fields = mutableMapOf<String, String>()
            for (line in paragraph.lineSequence()) {
                if (line.startsWith(' ') || !line.contains(':')) continue
                val index = line.indexOf(':')
                val key = line.substring(0, index)
                if (key == "Package" || key == "Status") {
                    if (fields.put(key, line.substring(index + 1).trim()) != null) {
                        blockers.add("Duplicate field: $key")
                    }
                }
            }
            val name = fields["Package"] ?: continue
            if (name !in CONFIGURE_ORDER) continue
            val status = fields["Status"] ?: ""
            if (statuses.put(name, status) != null) blockers.add("Duplicate package: $name")
        }

        val needed = mutableListOf<String>()
        for (name in CONFIGURE_ORDER) {
            when (val status = statuses[name]) {
                "install ok installed" -> Unit
                in allowedIncompleteStates -> needed.add(name)
                null -> blockers.add("Missing package status: $name")
                else -> blockers.add("Unsupported state for $name: $status")
            }
        }
        return Assessment(needed, blockers)
    }
}
