package com.example.engine

import com.example.data.model.Layer
import com.example.data.model.LayerType

/**
 * Layer groups on the flat layer list (index 0 = bottom). A group is a [LayerType.GROUP] layer;
 * its members have [Layer.parentId] = its id and sit directly below it, nested groups included:
 *
 *     [.. member, member, nested-group-member, nested-group, member, GROUP ..]
 */
object LayerTree {

    /** The group [layer] belongs to, if that group exists in [layers]; else null (top level). */
    fun parentOf(layers: List<Layer>, layer: Layer): String? {
        val p = layer.parentId ?: return null
        return if (layers.any { it.id == p && it.type == LayerType.GROUP && it.id != layer.id }) p else null
    }

    /** The layers directly in group [parent] (null = top level), bottom to top. */
    fun level(layers: List<Layer>, parent: String?): List<Layer> {
        val groups = layers.filter { it.type == LayerType.GROUP }.map { it.id }.toHashSet()
        return layers.filter { l -> (l.parentId?.takeIf { it in groups && it != l.id }) == parent }
    }

    /** Ids of everything inside group [groupId], nested groups' members included. */
    fun descendants(layers: List<Layer>, groupId: String): Set<String> {
        val out = LinkedHashSet<String>()
        var frontier = setOf(groupId)
        while (frontier.isNotEmpty()) {
            val next = layers.filter { it.parentId in frontier && it.id !in out && it.id != groupId }.map { it.id }.toSet()
            out.addAll(next)
            frontier = next
        }
        return out
    }

    /** Groups [layer] is inside, nearest first. */
    fun ancestors(layers: List<Layer>, layer: Layer): List<Layer> {
        val out = ArrayList<Layer>()
        var cur: Layer? = layer
        val seen = HashSet<String>()
        while (cur != null) {
            val p = parentOf(layers, cur) ?: break
            if (!seen.add(p)) break
            cur = layers.firstOrNull { it.id == p }
            if (cur != null) out.add(cur)
        }
        return out
    }

    /** Nesting depth of [layer] (0 = top level). */
    fun depth(layers: List<Layer>, layer: Layer): Int = ancestors(layers, layer).size

    /** True when [layer] or a group it is in is hidden. */
    fun effectivelyHidden(layers: List<Layer>, layer: Layer): Boolean =
        !layer.isVisible || ancestors(layers, layer).any { !it.isVisible }

    /** True when [layer] or a group it is in is locked. */
    fun effectivelyLocked(layers: List<Layer>, layer: Layer): Boolean =
        layer.isLocked || ancestors(layers, layer).any { it.isLocked }

    /** True when a group [layer] is in is collapsed (the panel hides it). */
    fun insideCollapsed(layers: List<Layer>, layer: Layer): Boolean =
        ancestors(layers, layer).any { it.isCollapsed }

    /**
     * A top-level layer that was inserted between a group's members (e.g. a new layer added
     * while a member was selected) joins that group, as in Photoshop.
     */
    fun adoptStrays(layers: MutableList<Layer>) {
        if (layers.none { it.type == LayerType.GROUP }) return
        for (i in layers.indices) {
            val l = layers[i]
            if (l.parentId != null) continue
            val above = layers.getOrNull(i + 1) ?: continue
            val below = layers.getOrNull(i - 1) ?: continue
            val group = if (above.type == LayerType.GROUP) above.id else above.parentId ?: continue
            if (group == l.id) continue
            val belowInGroup = below.parentId == group || ancestors(layers, below).any { it.id == group }
            if (belowInGroup) l.parentId = group
        }
        normalize(layers)
    }

    /**
     * Restores the group layout in place: dangling parent ids are cleared, and each group's
     * members (in their current order) are moved directly below it. Top-level order is kept.
     */
    fun normalize(layers: MutableList<Layer>) {
        val groups = layers.filter { it.type == LayerType.GROUP }.map { it.id }.toHashSet()
        for (l in layers) {
            val p = l.parentId
            if (p != null && (p !in groups || p == l.id)) l.parentId = null
        }
        // Break parent cycles (a group inside its own member).
        for (l in layers) {
            val seen = HashSet<String>()
            var cur: Layer? = l
            while (cur?.parentId != null) {
                if (!seen.add(cur.id)) { l.parentId = null; break }
                val pid = cur.parentId
                cur = layers.firstOrNull { it.id == pid }
            }
        }
        val out = ArrayList<Layer>(layers.size)
        fun emit(parent: String?) {
            for (l in layers.filter { it.parentId == parent }) {
                if (l.type == LayerType.GROUP) emit(l.id)
                out.add(l)
            }
        }
        emit(null)
        // Anything left out (should not happen) keeps its place at the end.
        for (l in layers) if (out.none { it === l }) out.add(l)
        layers.clear(); layers.addAll(out)
    }
}
