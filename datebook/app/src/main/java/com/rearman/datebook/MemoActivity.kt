package com.rearman.datebook

/**
 * The Memo Pad app. It has its own icon and task, as on the Palm,
 * and it uses the same records file as the Date Book and To Do List apps.
 */
class MemoActivity : MainActivity() {
    override val isMemoApp: Boolean get() = true
}
