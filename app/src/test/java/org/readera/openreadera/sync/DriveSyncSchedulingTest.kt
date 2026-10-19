package org.readera.openreadera.sync

import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DriveSyncSchedulingTest {
    @Test fun pageTurnBurstKeepsTheSamePendingSyncInsteadOfCancellingIt() {
        val context = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val manager = WorkManager.getInstance(context)
        DriveSyncWorker.enqueueImmediateSync(context)
        val first = manager.getWorkInfosForUniqueWork(DriveSyncWorker.IMMEDIATE_WORK_NAME).get().single()
        repeat(5) { DriveSyncWorker.enqueueImmediateSync(context) }
        val remaining = manager.getWorkInfosForUniqueWork(DriveSyncWorker.IMMEDIATE_WORK_NAME).get()
        assertEquals(listOf(first.id), remaining.map { it.id })
    }
}
