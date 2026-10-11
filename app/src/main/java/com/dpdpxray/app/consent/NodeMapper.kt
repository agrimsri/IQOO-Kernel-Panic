package com.dpdpxray.app.consent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dpdpxray.core.model.Bounds
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.screen.UiTreeLimits

/** Copies a live accessibility tree into the pure-Kotlin [UiNode] model, with depth and size limits. */
object NodeMapper {
    // Shared with core so a tree at a limit is known to be possibly cut off (C15 is then not established).
    private const val MAX_DEPTH = UiTreeLimits.MAX_DEPTH
    private const val MAX_NODES = UiTreeLimits.MAX_NODES

    fun map(root: AccessibilityNodeInfo?): UiNode? {
        root ?: return null
        var budget = MAX_NODES
        fun walk(n: AccessibilityNodeInfo, depth: Int): UiNode {
            budget--
            val r = Rect().also { n.getBoundsInScreen(it) }
            val kids = if (depth >= MAX_DEPTH) emptyList() else (0 until n.childCount).mapNotNull { i ->
                if (budget <= 0) null else n.getChild(i)?.let { walk(it, depth + 1) }
            }
            return UiNode(
                className = n.className?.toString().orEmpty(),
                text = n.text?.toString(),
                contentDescription = n.contentDescription?.toString(),
                isCheckable = n.isCheckable,
                isChecked = n.isChecked,
                isClickable = n.isClickable,
                isPassword = n.isPassword,
                isEditable = n.isEditable,
                bounds = Bounds(r.left, r.top, r.right, r.bottom),
                children = kids,
            )
        }
        return walk(root, 0)
    }

    /** Text a user would read for a clicked node: its own label, else its children's. */
    fun labelOf(n: AccessibilityNodeInfo?): String? {
        n ?: return null
        val own = listOfNotNull(n.text, n.contentDescription).joinToString(" ").trim()
        if (own.isNotEmpty()) return own
        return (0 until n.childCount).firstNotNullOfOrNull { i -> labelOf(n.getChild(i))?.takeIf { it.isNotBlank() } }
    }
}
