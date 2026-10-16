package com.rearman.datebook

/**
 * The To Do List app. It has its own icon and task, as on the Palm,
 * and it uses the same records as the Date Book app.
 */
class TodoActivity : MainActivity() {
    override val isTodoApp: Boolean get() = true
}
