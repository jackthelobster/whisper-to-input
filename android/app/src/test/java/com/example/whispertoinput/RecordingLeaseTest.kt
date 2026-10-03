package com.example.whispertoinput

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingLeaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun audio() = temporary.newFile().apply { writeText("recording") }

    @Test fun successfulOwnerDeletesRecording() {
        val file = audio()
        val lease = RecordingLease.acquire(file)
        lease.deleteAfterSuccess { true }
        assertFalse(file.exists())
        lease.release()
    }

    @Test fun cancelledAndFailedOperationsRetainRecording() {
        val file = audio()
        val lease = RecordingLease.acquire(file)
        lease.deleteAfterSuccess { false }
        lease.release()
        assertTrue(file.exists())
    }

    @Test fun olderOperationCannotDeleteOrReleaseNewerOwnersFile() {
        val file = audio()
        val old = RecordingLease.acquire(file)
        val current = RecordingLease.acquire(file)
        old.deleteAfterSuccess { true }
        old.release()
        assertTrue(file.exists())
        current.deleteAfterSuccess { true }
        assertFalse(file.exists())
        current.release()
    }

    @Test fun changedRecordingIsNotDeleted() {
        val file = audio()
        val lease = RecordingLease.acquire(file)
        file.writeText("a newly replaced recording")
        lease.deleteAfterSuccess { true }
        assertTrue(file.exists())
        lease.release()
    }
}
