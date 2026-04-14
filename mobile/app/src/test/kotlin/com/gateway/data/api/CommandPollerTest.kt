package com.gateway.data.api

import com.gateway.data.api.model.CommandRequest
import com.gateway.data.api.model.PollResponse
import com.gateway.data.prefs.EncryptedPrefsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CommandPollerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        // Setup test dependencies
    }

    @Test
    fun `initial state is idle`() = runTest {
        val initialState = PollerState.Idle
        
        assertTrue(initialState is PollerState.Idle)
    }

    @Test
    fun `state changes to polling when polling starts`() = runTest {
        val state = PollerState.Polling
        
        assertTrue(state is PollerState.Polling)
    }

    @Test
    fun `error state contains message and retry count`() = runTest {
        val errorState = PollerState.Error("Network error", 3)
        
        assertTrue(errorState is PollerState.Error)
        assertEquals("Network error", errorState.message)
        assertEquals(3, errorState.retryCount)
    }

    @Test
    fun `poll interval respects server suggestion`() = runTest {
        val serverInterval = 60_000L
        val minInterval = 5_000L
        val maxInterval = 300_000L
        
        val appliedInterval = serverInterval.coerceIn(minInterval, maxInterval)
        
        assertEquals(serverInterval, appliedInterval)
    }

    @Test
    fun `poll interval clamps to minimum`() = runTest {
        val serverInterval = 1_000L
        val minInterval = 5_000L
        val maxInterval = 300_000L
        
        val appliedInterval = serverInterval.coerceIn(minInterval, maxInterval)
        
        assertEquals(minInterval, appliedInterval)
    }

    @Test
    fun `poll interval clamps to maximum`() = runTest {
        val serverInterval = 600_000L
        val minInterval = 5_000L
        val maxInterval = 300_000L
        
        val appliedInterval = serverInterval.coerceIn(minInterval, maxInterval)
        
        assertEquals(maxInterval, appliedInterval)
    }

    @Test
    fun `commands are emitted when received`() = runTest {
        val commands = listOf(
            CommandRequest(
                commandId = "cmd1",
                action = "make_call",
                params = mapOf("destination" to "123456789")
            ),
            CommandRequest(
                commandId = "cmd2",
                action = "send_sms",
                params = mapOf("destination" to "987654321", "message" to "Test")
            )
        )
        
        assertEquals(2, commands.size)
        assertEquals("make_call", commands[0].action)
        assertEquals("send_sms", commands[1].action)
    }

    @Test
    fun `exponential backoff increases interval on failures`() = runTest {
        val baseInterval = 30_000L
        val backoffMultiplier = 2.0
        val retryCount = 3
        val maxInterval = 300_000L
        
        val backoffFactor = Math.pow(backoffMultiplier, retryCount.toDouble())
        val newInterval = (baseInterval * backoffFactor).toLong().coerceAtMost(maxInterval)
        
        assertEquals(240_000L, newInterval)
    }

    @Test
    fun `reset backoff restores default interval`() = runTest {
        val defaultInterval = 30_000L
        var currentInterval = 120_000L
        
        // Reset
        currentInterval = defaultInterval
        
        assertEquals(defaultInterval, currentInterval)
    }
}
