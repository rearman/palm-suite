package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncLogicTest {
    @Test fun noneApplyLiveAndSkipDeleted() {
        assertEquals(Action.APPLY, decide(LocalState.NONE, remoteDeleted = false, same = false, lastWins = false))
        assertEquals(Action.SKIP, decide(LocalState.NONE, remoteDeleted = true, same = false, lastWins = false))
    }

    @Test fun cleanApplyLiveAndDropDeleted() {
        assertEquals(Action.APPLY, decide(LocalState.CLEAN, remoteDeleted = false, same = false, lastWins = false))
        assertEquals(Action.DROP, decide(LocalState.CLEAN, remoteDeleted = true, same = false, lastWins = false))
    }

    @Test fun tombApplyLiveAndDropDeleted() {
        assertEquals(Action.APPLY, decide(LocalState.TOMB, remoteDeleted = false, same = false, lastWins = false))
        assertEquals(Action.DROP, decide(LocalState.TOMB, remoteDeleted = true, same = false, lastWins = false))
    }

    @Test fun dirtyLiveWithLastWinsRebase() {
        assertEquals(Action.REBASE, decide(LocalState.DIRTY, remoteDeleted = false, same = false, lastWins = true))
        assertEquals(Action.REBASE, decide(LocalState.DIRTY, remoteDeleted = false, same = true, lastWins = true))
    }

    @Test fun dirtyLiveSameAppliesAndDifferentCopiesThenApplies() {
        assertEquals(Action.APPLY, decide(LocalState.DIRTY, remoteDeleted = false, same = true, lastWins = false))
        assertEquals(Action.COPY_THEN_APPLY, decide(LocalState.DIRTY, remoteDeleted = false, same = false, lastWins = false))
    }

    @Test fun dirtyDeletedWithLastWinsRebase() {
        assertEquals(Action.REBASE, decide(LocalState.DIRTY, remoteDeleted = true, same = false, lastWins = true))
        assertEquals(Action.REBASE, decide(LocalState.DIRTY, remoteDeleted = true, same = true, lastWins = true))
    }

    @Test fun dirtyDeletedCopiesThenDrops() {
        assertEquals(Action.COPY_THEN_DROP, decide(LocalState.DIRTY, remoteDeleted = true, same = false, lastWins = false))
        assertEquals(Action.COPY_THEN_DROP, decide(LocalState.DIRTY, remoteDeleted = true, same = true, lastWins = false))
    }
}
