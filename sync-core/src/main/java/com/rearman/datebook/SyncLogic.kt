package com.rearman.datebook

/**
 * The sync state of a local row.
 * NONE: no local row for the uid.
 * CLEAN: a live row with no local change since the last sync.
 * DIRTY: a live row with a local change not yet pushed.
 * TOMB: a tombstone, deleted locally and not yet pushed.
 */
enum class LocalState { NONE, CLEAN, DIRTY, TOMB }

/** What the sync engine does with one remote change. */
enum class Action {
    /** SKIP: nothing to do. */
    SKIP,
    /** APPLY: write the remote version to the local row. */
    APPLY,
    /** DROP: delete the local row. */
    DROP,
    /** COPY_THEN_APPLY: save the local record as a new record, then APPLY. */
    COPY_THEN_APPLY,
    /** COPY_THEN_DROP: save the local record as a new record, then DROP. */
    COPY_THEN_DROP,
    /** REBASE: keep the local version dirty and take the remote rev as its base, so the next push sends the local version again. */
    REBASE
}

/**
 * Decides what to do with one remote change for a uid.
 *
 * local: the state of the local row for the uid.
 * remoteDeleted: the remote change is a delete.
 * same: the local record has the same content as the remote record (dupKey).
 * lastWins: the record type is the ShortCuts list, where the local version always wins.
 *
 * A conflict is a DIRTY local row with a remote change. It keeps both copies, as Palm HotSync did:
 * the local version is saved as a new record (COPY_THEN_APPLY or COPY_THEN_DROP), and the remote
 * version takes the record. With lastWins the local version wins instead and is pushed again (REBASE).
 */
fun decide(local: LocalState, remoteDeleted: Boolean, same: Boolean, lastWins: Boolean): Action =
    when (local) {
        LocalState.NONE -> if (remoteDeleted) Action.SKIP else Action.APPLY
        LocalState.CLEAN -> if (remoteDeleted) Action.DROP else Action.APPLY
        LocalState.DIRTY -> when {
            lastWins -> Action.REBASE
            remoteDeleted -> Action.COPY_THEN_DROP
            same -> Action.APPLY
            else -> Action.COPY_THEN_APPLY
        }
        LocalState.TOMB -> if (remoteDeleted) Action.DROP else Action.APPLY
    }
