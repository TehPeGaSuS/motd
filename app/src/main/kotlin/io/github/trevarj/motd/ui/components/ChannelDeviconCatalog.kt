// Loader for GENERATED data: app/src/main/resources/channel-devicons-index.json and
// channel-devicons/*.json are emitted by tools/gen-channel-devicons/generate.py from
// devicons/devicon v2.16.0. Do not hand-edit generated resources.
// Source: https://github.com/devicons/devicon (MIT). See THIRD_PARTY_NOTICES.md.
package io.github.trevarj.motd.ui.components

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lightweight catalog entry; SVG paths stay in a per-mark resource until selected. */
@Serializable
private class CatalogEntry(
    val name: String,
    val aliases: List<String>,
    val w: Float,
    val h: Float,
)

/** An SVG path plus the source fill rule; evenodd keeps counters from filling solid. */
@Serializable
private class CatalogPath(
    val evenOdd: Boolean,
    val d: String,
)

/** Generated devicon metadata with per-mark lazy path loading. */
internal object ChannelDeviconCatalog {
    private const val INDEX_RESOURCE = "/channel-devicons-index.json"
    private const val PATH_RESOURCE_DIR = "/channel-devicons"
    private val json = Json { ignoreUnknownKeys = true }

    val marks: List<CatalogChannelMark> by lazy(LazyThreadSafetyMode.NONE) {
        val raw = readResource(INDEX_RESOURCE)
        json.decodeFromString<List<CatalogEntry>>(raw).map { entry ->
            CatalogChannelMark(
                markName = entry.name,
                aliases = entry.aliases.toSet(),
                viewportWidth = entry.w,
                viewportHeight = entry.h,
                pathDataLoader = { loadPathData(entry.name) },
            )
        }
    }

    private fun loadPathData(name: String): List<Pair<Boolean, String>> =
        json
            .decodeFromString<List<CatalogPath>>(readResource("$PATH_RESOURCE_DIR/$name.json"))
            .map { path -> path.evenOdd to path.d }

    private fun readResource(path: String): String =
        checkNotNull(ChannelDeviconCatalog::class.java.getResourceAsStream(path)) {
            "missing packaged resource $path"
        }.use { stream -> stream.readBytes().decodeToString() }
}
