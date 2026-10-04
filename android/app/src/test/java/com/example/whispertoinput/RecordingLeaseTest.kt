package com.example.whispertoinput

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingLeaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun audio() = temporary.newFile().apply { writeText("recording") }

    @Test fun uploadCompletionRetainsRecordingUntilEditorInsertion() {
        val file = audio()
        val lease = RecordingLease.acquire(file)
        // Completing the upload only releases its lease. The service callback may still be
        // queued, or commitText may fail; both cases must leave the recording retryable.
        lease.release()
        assertTrue(file.exists())
        assertEquals("recording", file.readText())

        // Even a late cleanup attempt by the completed upload must not delete the audio.
        lease.deleteAfterSuccess { true }
        assertTrue(file.exists())

        // Only the editor/session owner deletes after insertion succeeds.
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test fun completedUploadCannotDeleteRetryRecording() {
        val file = audio()
        val completed = RecordingLease.acquire(file)
        completed.release()
        assertTrue(file.exists())
        val retry = RecordingLease.acquire(file)
        try {
            completed.release()
            completed.deleteAfterSuccess { true }
            assertTrue(file.exists())
            // The old completion must not release the retry's ownership either.
            retry.deleteAfterSuccess { true }
            assertFalse(file.exists())
        } finally {
            retry.release()
        }
    }

    @Test fun sessionCleanupCanDeleteWhileUploadLeaseIsHeld() {
        val file = audio()
        val lease = RecordingLease.acquire(file)
        try {
            // Cancellation/hidden-editor cleanup belongs to the service and is not blocked
            // by an upload lease; finishing that upload cannot restore the discarded audio.
            assertTrue(file.delete())
            assertFalse(file.exists())
        } finally {
            lease.release()
        }
        assertFalse(file.exists())
    }

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
