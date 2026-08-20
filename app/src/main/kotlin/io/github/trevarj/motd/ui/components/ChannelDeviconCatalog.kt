// Loader for GENERATED data: app/src/main/resources/channel-devicons.json is emitted by
// tools/gen-channel-devicons/generate.py from devicons/devicon v2.16.0 - do not hand-edit the JSON.
// Source: https://github.com/devicons/devicon (MIT). See THIRD_PARTY_NOTICES.md.
package io.github.trevarj.motd.ui.components

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One generated catalog entry as stored in the packaged resource. */
@Serializable
private class CatalogEntry(
    val name: String,
    val aliases: List<String>,
    val w: Float,
    val h: Float,
    val paths: List<CatalogPath>,
)

/** An SVG path plus the source fill rule; evenodd keeps counters from filling solid. */
@Serializable
private class CatalogPath(
    val evenOdd: Boolean,
    val d: String,
)

/**
 * The generated devicon marks, read from a packaged java resource.
 *
 * A classloader read rather than an Android asset on purpose: [matchedChannelDevicon] is pure JVM
 * and is exercised from plain unit tests that have no `Context` to hand it.
 */
internal object ChannelDeviconCatalog {
    private const val RESOURCE = "/channel-devicons.json"

    private val json = Json { ignoreUnknownKeys = true }

    // ponytail: one eager parse of ~590KB on the first channel badge, then cached for the process.
    // Split into an alias index with lazily fetched path data only if it ever measures on a cold
    // chat list.
    val marks: List<CatalogChannelMark> by lazy(LazyThreadSafetyMode.NONE) {
        val raw =
            checkNotNull(ChannelDeviconCatalog::class.java.getResourceAsStream(RESOURCE)) {
                "missing packaged resource $RESOURCE"
            }.use { stream -> stream.readBytes().decodeToString() }

        json.decodeFromString<List<CatalogEntry>>(raw).map { entry ->
            CatalogChannelMark(
                markName = entry.name,
                aliases = entry.aliases.toSet(),
                viewportWidth = entry.w,
                viewportHeight = entry.h,
                pathData = entry.paths.map { path -> path.evenOdd to path.d },
            )
        }
    }
}
