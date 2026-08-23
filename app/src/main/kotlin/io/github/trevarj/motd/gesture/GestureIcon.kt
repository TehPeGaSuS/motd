package io.github.trevarj.motd.gesture

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Curated icon vocabulary for gesture menu nodes.
 *
 * Deliberately a small closed set rather than a free-form icon name: the persisted menu has to
 * survive round-trips through older builds, and a stored name that no longer resolves would leave a
 * slice with no glyph at all. [UNKNOWN] is the decode fallback (see [GestureIconSerializer]) and is
 * also what an icon written by a newer build degrades to.
 */
@Serializable(with = GestureIconSerializer::class)
enum class GestureIcon {
    UNKNOWN,
    MENU,
    FOLDER,
    CHAT,
    PIN,
    STAR,
    UNREAD,
    MARK_READ,
    PEOPLE,
    PERSON,
    MENTION,
    SEARCH,
    INFO,
    BOLT,
    AWAY,
    NETWORK,
    GLOBE,
    ATTACH,
    LIGHT_MODE,
    DARK_MODE,
    REFRESH,
    POWER,
    LINK,
    MORE,
}

/** Vector for a menu slice. Lives apart from the enum so pure model tests never touch Compose. */
val GestureIcon.vector: ImageVector
    get() =
        when (this) {
            GestureIcon.UNKNOWN -> Icons.Outlined.QuestionMark
            GestureIcon.MENU -> Icons.Outlined.Apps
            GestureIcon.FOLDER -> Icons.Outlined.Folder
            GestureIcon.CHAT -> Icons.Outlined.Forum
            GestureIcon.PIN -> Icons.Outlined.PushPin
            GestureIcon.STAR -> Icons.Outlined.Star
            GestureIcon.UNREAD -> Icons.Outlined.MarkChatUnread
            GestureIcon.MARK_READ -> Icons.Outlined.MarkChatRead
            GestureIcon.PEOPLE -> Icons.Outlined.People
            GestureIcon.PERSON -> Icons.Outlined.Person
            GestureIcon.MENTION -> Icons.Outlined.AlternateEmail
            GestureIcon.SEARCH -> Icons.Outlined.Search
            GestureIcon.INFO -> Icons.Outlined.Info
            GestureIcon.BOLT -> Icons.Outlined.Bolt
            GestureIcon.AWAY -> Icons.Outlined.Bedtime
            GestureIcon.NETWORK -> Icons.Outlined.Lan
            GestureIcon.GLOBE -> Icons.Outlined.Public
            GestureIcon.ATTACH -> Icons.Outlined.AttachFile
            GestureIcon.LIGHT_MODE -> Icons.Outlined.LightMode
            GestureIcon.DARK_MODE -> Icons.Outlined.DarkMode
            GestureIcon.REFRESH -> Icons.Outlined.Refresh
            GestureIcon.POWER -> Icons.Outlined.PowerSettingsNew
            GestureIcon.LINK -> Icons.Outlined.Link
            GestureIcon.MORE -> Icons.Outlined.MoreHoriz
        }

/** Unknown icon names decode to [GestureIcon.UNKNOWN] instead of failing the whole menu. */
object GestureIconSerializer : FallbackEnumSerializer<GestureIcon>(
    serialName = "io.github.trevarj.motd.gesture.GestureIcon",
    entries = GestureIcon.entries,
    fallback = GestureIcon.UNKNOWN,
)
