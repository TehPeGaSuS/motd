package io.github.trevarj.motd.ui.chat

import androidx.paging.ItemSnapshotList
import io.github.trevarj.motd.data.db.MessageEntity

/**
 * One Paging PRESENTATION of the timeline, reduced to the numbers that decide where the viewport
 * ends up.
 *
 * The chat list is `reverseLayout`, and the paging query orders newest-first, so index 0 is the
 * newest row, [placeholdersBefore] counts unloaded slots on the NEWER side of the loaded window,
 * and [loadedIds] runs newest to oldest.
 *
 * Deliberately free of Paging and Compose types: every rule derived from it is a pure function over
 * three integers and a list, which is the only way the viewport pin's decision can be tested at all
 * (a scroll correction that fires wrongly is worse than none).
 */
internal data class TimelineWindow(
    /** Presented rows, placeholders included. */
    val itemCount: Int,
    /** Unloaded rows before the loaded window; equivalently the loaded window's start index. */
    val placeholdersBefore: Int,
    /** Row ids of the loaded window, in presentation order (newest first). */
    val loadedIds: List<Long>,
) {
    val loadedCount: Int get() = loadedIds.size

    /** Index of the newest loaded row, or -1 when the window holds nothing. */
    val loadedFirstIndex: Int get() = if (loadedIds.isEmpty()) -1 else placeholdersBefore

    /** Index of the oldest loaded row, or -1 when the window holds nothing. */
    val loadedLastIndex: Int
        get() = if (loadedIds.isEmpty()) -1 else placeholdersBefore + loadedIds.size - 1

    /** Presentation index of [id], or -1 when it is outside the loaded window. */
    fun indexOf(id: Long): Int = loadedIds.indexOf(id).let { if (it < 0) -1 else placeholdersBefore + it }

    /** Row id occupying [index], or null when that slot is a placeholder or out of range. */
    fun idAt(index: Int): Long? = loadedIds.getOrNull(index - placeholdersBefore)
}

/** Where the viewport sat, as of one measured layout, plus the row identity that owned that slot. */
internal data class TimelineViewportAnchor(
    val index: Int,
    val offset: Int,
    /** Row id at [index] in the presentation the layout was measured against; null for a placeholder. */
    val key: Long?,
)

/**
 * What became of the viewport's anchor row in a new presentation.
 *
 * These are the mutually exclusive explanations for on-screen churn after a history fetch, and the
 * whole point of the generation watch is that a journal line names one of them instead of leaving
 * every hypothesis alive at once.
 */
internal enum class TimelineAnchorFate {
    /** The viewport was already parked on a placeholder, so there is no identity to follow. */
    NO_ANCHOR,

    /** The new presentation has no rows at all — a transient empty snapshot. */
    EMPTY,

    /** The anchor is still a loaded row; `LazyListState`'s own key map re-anchors the viewport. */
    LOADED,

    /** The anchor's slot is a placeholder now, so its key left the list and key anchoring is dead. */
    PLACEHOLDER,
}

internal fun timelineAnchorFate(
    key: Long?,
    current: TimelineWindow,
): TimelineAnchorFate =
    when {
        key == null -> TimelineAnchorFate.NO_ANCHOR
        current.itemCount == 0 -> TimelineAnchorFate.EMPTY
        current.indexOf(key) >= 0 -> TimelineAnchorFate.LOADED
        else -> TimelineAnchorFate.PLACEHOLDER
    }

/**
 * The journal payload for one presentation transition.
 *
 * Field names are snake_case and carry classification, ids, counts and indices only. None of them
 * is named `reason`: [io.github.trevarj.motd.diagnostics.DiagnosticLogger] redacts that name.
 *
 * How to read a run of these lines:
 *
 *  - `item_count` collapsing to 0 for one line and recovering is a transient empty snapshot.
 *  - `loaded_first_index`/`loaded_last_index` moving while `before_index` stays put is the loaded
 *    window being re-placed under a stationary viewport: the rows outside it present as
 *    placeholders, which is the skeleton flash.
 *  - `anchor_fate=placeholder` is the case where key-based scroll anchoring cannot work, because the
 *    anchor's key is no longer in the list at all.
 *  - `anchor_drift` (`after_index - before_index`) is what the viewport actually did. Non-zero with
 *    `anchor_fate=loaded` is Compose correctly following the row; non-zero with
 *    `anchor_fate=placeholder` is the viewport being moved by something other than the anchor.
 *  - `anchor_index` is where the anchor row IS in the new presentation (-1 when unloaded). Compare
 *    it with `after_index`: a mismatch is the displacement a viewport pin exists to remove.
 */
internal fun timelineGenerationFields(
    generation: Long,
    previous: TimelineWindow?,
    current: TimelineWindow,
    before: TimelineViewportAnchor,
    after: TimelineViewportAnchor,
    settled: Boolean,
    scrolling: Boolean,
    following: Boolean,
): Map<String, Any?> =
    mapOf(
        "generation" to generation,
        "item_count" to current.itemCount,
        "placeholders_before" to current.placeholdersBefore,
        "loaded_count" to current.loadedCount,
        "loaded_first_index" to current.loadedFirstIndex,
        "loaded_last_index" to current.loadedLastIndex,
        "prev_item_count" to (previous?.itemCount ?: -1),
        "prev_placeholders_before" to (previous?.placeholdersBefore ?: -1),
        "prev_loaded_count" to (previous?.loadedCount ?: -1),
        "prev_loaded_first_index" to (previous?.loadedFirstIndex ?: -1),
        "prev_loaded_last_index" to (previous?.loadedLastIndex ?: -1),
        "before_index" to before.index,
        "before_offset" to before.offset,
        "before_key" to (before.key ?: -1L),
        "after_index" to after.index,
        "after_offset" to after.offset,
        "after_key" to (after.key ?: -1L),
        "anchor_fate" to timelineAnchorFate(before.key, current).name.lowercase(),
        "anchor_index" to (before.key?.let(current::indexOf) ?: -1),
        "anchor_drift" to (after.index - before.index),
        "settled" to settled,
        "scrolling" to scrolling,
        "following" to following,
    )

/**
 * Renders a timeline journal payload for the logcat trace, which takes one flat string. Shared by
 * the generation watch and the viewport pin so the two read identically in an exported log.
 */
internal fun formatTimelineGenerationFields(fields: Map<String, Any?>): String = fields.entries.joinToString(" ") { (key, value) -> "$key=$value" }

/**
 * Reads one Paging presentation into the pure model.
 *
 * `items` is the loaded window only, so no placeholder is ever dereferenced here.
 */
internal fun ItemSnapshotList<MessageEntity>.toTimelineWindow(): TimelineWindow =
    TimelineWindow(
        itemCount = size,
        placeholdersBefore = placeholdersBefore,
        loadedIds = items.map(MessageEntity::id),
    )
