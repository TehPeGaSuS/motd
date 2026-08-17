package io.github.trevarj.motd.gesture

/**
 * Pure tree algebra for the gesture menu, in the shape of `DrawerReorder`: no Compose, no Android,
 * no ViewModel state — every edit is a function from one [GestureMenuConfig] to the next, so the
 * editor can preview, undo, and validate without a device.
 *
 * Every operation that cannot apply — unknown id, wrong node kind, a move past either end, an edit
 * that would move a node inside itself — returns the config unchanged rather than clamping to some
 * nearby result. A refused edit must look refused, not quietly land somewhere else.
 */

/** Preorder walk of the whole tree, root first. */
fun GestureMenuConfig.allNodes(): List<GestureNode> = root.flattenNodes()

private fun GestureNode.flattenNodes(): List<GestureNode> =
    listOf(this) + childNodes.flatMap { it.flattenNodes() }

fun GestureMenuConfig.findNode(nodeId: String): GestureNode? = allNodes().firstOrNull { it.id == nodeId }

/** Id of the submenu holding [nodeId], or null for the root and for ids that are not in the tree. */
fun GestureMenuConfig.parentIdOf(nodeId: String): String? =
    allNodes().firstOrNull { node -> node.childNodes.any { it.id == nodeId } }?.id

/** Add [child] to the submenu [parentId]; ignored when the id is already taken or the parent is not a ring. */
fun GestureMenuConfig.addChild(parentId: String, child: GestureNode, index: Int? = null): GestureMenuConfig {
    if (findNode(child.id) != null) return this
    if (findNode(parentId) !is GestureNode.Submenu) return this
    return mapNodes { node ->
        if (node.id == parentId && node is GestureNode.Submenu) {
            node.copy(children = node.children.inserted(child, index))
        } else {
            node
        }
    }
}

/** Drop [nodeId] and everything under it. The root is not removable. */
fun GestureMenuConfig.removeNode(nodeId: String): GestureMenuConfig {
    if (nodeId == root.id || findNode(nodeId) == null) return this
    return mapNodes { node -> if (node.id == nodeId) null else node }
}

/** Move [nodeId] by [delta] places inside its own ring. Never changes rings. */
fun GestureMenuConfig.moveAmongSiblings(nodeId: String, delta: Int): GestureMenuConfig {
    if (delta == 0) return this
    val parentId = parentIdOf(nodeId) ?: return this
    val parent = findNode(parentId) as? GestureNode.Submenu ?: return this
    val from = parent.children.indexOfFirst { it.id == nodeId }
    val to = from + delta
    if (from < 0 || to !in parent.children.indices) return this
    return mapNodes { node ->
        if (node.id == parentId && node is GestureNode.Submenu) node.copy(children = node.children.moved(from, to)) else node
    }
}

/**
 * Move [nodeId] (with its subtree) into the submenu [newParentId]. Refused when the target is the
 * node itself or one of its own descendants — a menu is a tree, and a cycle would hang the walk.
 */
fun GestureMenuConfig.reparent(nodeId: String, newParentId: String, index: Int? = null): GestureMenuConfig {
    if (nodeId == root.id || nodeId == newParentId) return this
    val node = findNode(nodeId) ?: return this
    if (findNode(newParentId) !is GestureNode.Submenu) return this
    if (node.flattenNodes().any { it.id == newParentId }) return this
    return removeNode(nodeId).addChild(newParentId, node, index)
}

/** Replace [nodeId] with [transform]'s result; a rename onto an id already in the tree is refused. */
fun GestureMenuConfig.updateNode(nodeId: String, transform: (GestureNode) -> GestureNode): GestureMenuConfig {
    val current = findNode(nodeId) ?: return this
    val updated = transform(current)
    if (updated.id != nodeId && findNode(updated.id) != null) return this
    return mapNodes { node -> if (node.id == nodeId) updated else node }
}

/** Point a leaf at a different action. Nodes that are not leaves keep what they are. */
fun GestureMenuConfig.bindAction(nodeId: String, action: GestureAction): GestureMenuConfig =
    updateNode(nodeId) { node -> (node as? GestureNode.Leaf)?.copy(action = action) ?: node }

/** Something about a menu that would not render or behave, reported per node for inline editor chips. */
sealed interface GestureMenuViolation {
    val nodeId: String

    /** A ring with more slices than a thumb can hit. */
    data class RingOverflow(override val nodeId: String, val slices: Int) : GestureMenuViolation

    /** A ring nested past [MAX_GESTURE_RINGS]; [ring] is the ring this node would have opened. */
    data class TooDeep(override val nodeId: String, val ring: Int) : GestureMenuViolation

    /** A slice with nothing to label it. */
    data class BlankLabel(override val nodeId: String) : GestureMenuViolation

    /** The same id used twice, which would make every edit ambiguous. */
    data class DuplicateId(override val nodeId: String) : GestureMenuViolation
}

/**
 * Everything wrong with [config], in preorder.
 *
 * Ring capacity counts a provider as the entries it fans out (its clamped limit), because that is
 * what the ring behind it actually shows; in its own parent's ring it is one slice like anything
 * else. Nodes this build does not understand are exempt from the label rule: their text belongs to
 * whichever build wrote them.
 */
fun validateGestureMenu(config: GestureMenuConfig): List<GestureMenuViolation> {
    val violations = mutableListOf<GestureMenuViolation>()
    val nodes = config.allNodes()

    nodes.groupBy { it.id }
        .filter { (_, sharing) -> sharing.size > 1 }
        .keys
        .forEach { violations += GestureMenuViolation.DuplicateId(it) }

    fun walk(node: GestureNode, ring: Int) {
        if (node !is GestureNode.Unknown && node.label.isBlank()) {
            violations += GestureMenuViolation.BlankLabel(node.id)
        }
        if (node.opensRing) {
            if (ring > MAX_GESTURE_RINGS) violations += GestureMenuViolation.TooDeep(node.id, ring)
            if (node.ringSlices > MAX_RING_SLICES) {
                violations += GestureMenuViolation.RingOverflow(node.id, node.ringSlices)
            }
        }
        node.childNodes.forEach { walk(it, ring + 1) }
    }
    walk(config.root, ring = 1)
    return violations
}

/** True when [config] can be saved as-is. */
fun isValidGestureMenu(config: GestureMenuConfig): Boolean = validateGestureMenu(config).isEmpty()

/**
 * Rebuild the tree, applying [transform] to every node below the root and to the root itself.
 * Returning null drops that node and its subtree; the root is never dropped and never stops being a
 * submenu, so a menu always has a ring to open.
 */
private fun GestureMenuConfig.mapNodes(transform: (GestureNode) -> GestureNode?): GestureMenuConfig {
    val mappedRoot = transform(root) as? GestureNode.Submenu ?: root
    return copy(root = mappedRoot.transformChildren(transform))
}

private fun GestureNode.Submenu.transformChildren(transform: (GestureNode) -> GestureNode?): GestureNode.Submenu {
    val mapped = children.mapNotNull { child ->
        when (val replaced = transform(child)) {
            null -> null
            is GestureNode.Submenu -> replaced.transformChildren(transform)
            else -> replaced
        }
    }
    return copy(children = mapped)
}

private fun <T> List<T>.inserted(value: T, index: Int?): List<T> {
    val at = index?.coerceIn(0, size) ?: size
    return toMutableList().apply { add(at, value) }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
