package io.github.trevarj.motd.gesture

/**
 * The menu a user gets before editing anything: one full ring of eight.
 *
 * Ids are stable constants rather than fresh UUIDs so an untouched menu stays *equal* to the
 * default — that equality is what lets preferences avoid persisting an unedited tree and lets a
 * configuration backup skip exporting one.
 *
 * Labels are plain strings, not resources: they are user data the editor can rewrite, and a stored
 * menu must not change meaning because the app's resources did.
 */
val DEFAULT_GESTURE_ROOT: GestureNode.Submenu = GestureNode.Submenu(
    id = "default-root",
    label = "Menu",
    icon = GestureIcon.MENU,
    children = listOf(
        GestureNode.Provider(
            id = "default-unread",
            label = "Unread",
            icon = GestureIcon.UNREAD,
            kind = GestureProviderKind.UNREAD_CHATS,
        ),
        GestureNode.Provider(
            id = "default-pinned",
            label = "Pinned",
            icon = GestureIcon.PIN,
            kind = GestureProviderKind.PINNED_CHATS,
        ),
        GestureNode.Provider(
            id = "default-friends",
            label = "Friends",
            icon = GestureIcon.PEOPLE,
            kind = GestureProviderKind.FRIENDS,
        ),
        GestureNode.Leaf(
            id = "default-next-unread",
            label = "Next unread",
            icon = GestureIcon.BOLT,
            action = GestureAction.NextUnread,
        ),
        GestureNode.Leaf(
            id = "default-mark-all-read",
            label = "Mark all read",
            icon = GestureIcon.MARK_READ,
            action = GestureAction.MarkAllRead,
        ),
        GestureNode.Leaf(
            id = "default-away",
            label = "Away",
            icon = GestureIcon.AWAY,
            action = GestureAction.ToggleAway(),
        ),
        GestureNode.Submenu(
            id = "default-tools",
            label = "Tools",
            icon = GestureIcon.FOLDER,
            children = listOf(
                GestureNode.Leaf(
                    id = "default-search",
                    label = "Search",
                    icon = GestureIcon.SEARCH,
                    action = GestureAction.OpenSearch,
                ),
                GestureNode.Leaf(
                    id = "default-channel-info",
                    label = "Channel info",
                    icon = GestureIcon.INFO,
                    action = GestureAction.ChannelInfoCurrent,
                ),
                GestureNode.Leaf(
                    id = "default-attach",
                    label = "Attach",
                    icon = GestureIcon.ATTACH,
                    action = GestureAction.AttachCurrent,
                ),
                GestureNode.Leaf(
                    id = "default-theme",
                    label = "Light/dark",
                    icon = GestureIcon.LIGHT_MODE,
                    action = GestureAction.ToggleTheme,
                ),
            ),
        ),
        GestureNode.Provider(
            id = "default-networks",
            label = "Networks",
            icon = GestureIcon.NETWORK,
            kind = GestureProviderKind.NETWORKS,
        ),
    ),
)

/** The stock menu. [GestureMenuConfig] defaults to exactly this tree. */
fun defaultGestureMenu(): GestureMenuConfig = GestureMenuConfig()
