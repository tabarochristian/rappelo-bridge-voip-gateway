package com.gateway.domain.queue

import com.gateway.data.db.dao.QueuedCallDao
import com.gateway.data.db.entity.QueuedCallEntity
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.data.prefs.QueueOverflowStrategy
import com.gateway.queue.CallQueueImpl
import com.gateway.queue.model.CallDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallQueueImplTest {

    private val dao: QueuedCallDao = mock()
    private val prefsManager: EncryptedPrefsManager = mock()
    private lateinit var callQueue: CallQueueImpl

    private val fakeEntity = QueuedCallEntity(
        id = 1L,
        callerId = "1234567890",
        direction = CallDirection.GSM_OUTBOUND.name,
        status = "WAITING",
        simSlot = 0,
        enqueuedAt = 0L
    )

    @Before
    fun setup() {
        // Stub observeActiveCalls so the init block collector doesn't throw
        whenever(dao.observeActiveCalls()).thenReturn(MutableStateFlow(emptyList()))
        whenever(prefsManager.getMaxQueueSize()).thenReturn(50)
        whenever(prefsManager.getQueueOverflowStrategy()).thenReturn(QueueOverflowStrategy.REJECT)

        callQueue = CallQueueImpl(dao, prefsManager)
    }

    @Test
    fun `initial queue state is empty`() = runTest {
        assertTrue(callQueue.queueState.first().isEmpty())
    }

    @Test
    fun `enqueueCall inserts into dao when capacity available`() = runTest {
        whenever(dao.getActiveCount()).thenReturn(0)

        val result = callQueue.enqueueCall("1234567890", CallDirection.GSM_OUTBOUND)

        assertTrue(result)
        verify(dao).insert(any())
    }

    @Test
    fun `enqueueCall returns false when queue full and strategy is REJECT`() = runTest {
        whenever(dao.getActiveCount()).thenReturn(50)

        val result = callQueue.enqueueCall("1234567890", CallDirection.GSM_OUTBOUND)

        assertFalse(result)
    }

    @Test
    fun `removeCall calls deleteById on dao`() = runTest {
        callQueue.removeCall(1L)
        verify(dao).deleteById(1L)
    }

    @Test
    fun `markCallActive updates status when entity exists`() = runTest {
        whenever(dao.getByCallerId("1234567890")).thenReturn(fakeEntity)

        callQueue.markCallActive("1234567890")

        verify(dao).updateStatus(1L, "ACTIVE")
    }

    @Test
    fun `markCallCompleted updates status when entity exists`() = runTest {
        whenever(dao.getByCallerId("1234567890")).thenReturn(fakeEntity)

        callQueue.markCallCompleted("1234567890", com.gateway.telephony.gsm.EndReason.NORMAL)

        verify(dao).updateStatus(1L, "COMPLETED")
    }

    @Test
    fun `markCallFailed updates status when entity exists`() = runTest {
        whenever(dao.getByCallerId("1234567890")).thenReturn(fakeEntity)

        callQueue.markCallFailed("1234567890", "Network error")

        verify(dao).updateStatus(1L, "FAILED")
    }

    @Test
    fun `active call count starts at zero`() = runTest {
        assertEquals(0, callQueue.activeCallCount.first())
    }

    @Test
    fun `waiting call count starts at zero`() = runTest {
        assertEquals(0, callQueue.waitingCallCount.first())
    }

    @Test
    fun `hasCapacity returns true when queue is empty`() {
        assertTrue(callQueue.hasCapacity())
    }
}

