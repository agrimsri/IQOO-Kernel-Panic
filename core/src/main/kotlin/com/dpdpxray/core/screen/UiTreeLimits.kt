package com.dpdpxray.core.screen

import com.dpdpxray.core.model.UiNode

/**
 * Size limits the phone applies when it copies an accessibility tree (app `NodeMapper`). A tree at a limit may have
 * been cut off, so "nothing found in it" is not established for such a tree.
 */
object UiTreeLimits {
    const val MAX_NODES = 600
    const val MAX_DEPTH = 30

    fun possiblyTruncated(root: UiNode): Boolean = root.flatten().size >= MAX_NODES || depth(root) >= MAX_DEPTH

    private fun depth(n: UiNode): Int = if (n.children.isEmpty()) 0 else 1 + n.children.maxOf(::depth)
}
