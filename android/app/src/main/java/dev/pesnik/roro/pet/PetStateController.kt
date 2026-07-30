package dev.pesnik.roro.pet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the pet's mood, shared by ChatViewModel (which
 * requests transitions as chat lifecycle events happen) and PetOverlayService
 * (which renders whatever this holds) — both run in the same process, so a
 * plain singleton is enough; no IPC needed.
 *
 * Ports the priority + minDisplay + autoReturn arbitration from the desktop
 * app's state-priority.js + theme.json timings: a higher-priority state
 * pre-empts immediately; a same-or-lower priority request is queued until the
 * current state's minDisplay has elapsed; states with autoReturnMs revert to
 * Idle automatically unless pre-empted first.
 */
object PetStateController {

    private val _state = MutableStateFlow(PetState.Idle)
    val state: StateFlow<PetState> = _state.asStateFlow()

    // Serializes all mutation of the vars below onto one coroutine at a time,
    // since request() can be called concurrently from ChatViewModel's
    // viewModelScope and PetOverlayService's own scope.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var pendingJob: Job? = null
    private var autoReturnJob: Job? = null
    private var currentEnteredAt: Long = 0L

    fun request(newState: PetState) {
        scope.launch { requestInternal(newState) }
    }

    private fun requestInternal(newState: PetState) {
        val current = _state.value
        if (newState == current) {
            if (newState.autoReturnMs != null) scheduleAutoReturn(newState)
            return
        }

        pendingJob?.cancel()
        pendingJob = null

        if (newState.priority > current.priority) {
            applyImmediately(newState)
            return
        }

        val elapsed = System.currentTimeMillis() - currentEnteredAt
        val remaining = current.minDisplayMs - elapsed
        if (remaining <= 0) {
            applyImmediately(newState)
        } else {
            pendingJob = scope.launch {
                delay(remaining)
                applyImmediately(newState)
            }
        }
    }

    private fun applyImmediately(newState: PetState) {
        autoReturnJob?.cancel()
        autoReturnJob = null
        currentEnteredAt = System.currentTimeMillis()
        _state.value = newState
        if (newState.autoReturnMs != null) scheduleAutoReturn(newState)
    }

    private fun scheduleAutoReturn(state: PetState) {
        autoReturnJob?.cancel()
        val ms = state.autoReturnMs ?: return
        autoReturnJob = scope.launch {
            delay(ms)
            applyImmediately(PetState.Idle)
        }
    }
}
