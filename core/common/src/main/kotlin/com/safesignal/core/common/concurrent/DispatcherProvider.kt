package com.safesignal.core.common.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatchers.
 *
 * SafeSignal's audio path must never run on the main thread, and the recorder
 * thread must never be starved by UI work. Routing every `withContext` through
 * this interface keeps those guarantees testable (`UnconfinedTestDispatcher` in
 * unit tests, real dispatchers in production).
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher

    /**
     * Dedicated dispatcher for audio capture work.
     *
     * Separate from [io] on purpose: a burst of database or network I/O must
     * never delay `AudioRecord.read()`, because a starved reader shows up as
     * dropped microphone frames and therefore as evidence quality loss.
     */
    val audio: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val audio: CoroutineDispatcher get() = Dispatchers.Default
}