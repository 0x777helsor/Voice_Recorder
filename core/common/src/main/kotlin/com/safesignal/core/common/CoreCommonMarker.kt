package com.safesignal.core.common

/**
 * Marker for the shared core layer.
 *
 * The module deliberately contains no Android framework dependencies beyond
 * what is unavoidable, so that the parts of SafeSignal that matter most
 * (state transitions, crypto envelope handling, integrity verification,
 * segmentation) can be unit tested on the JVM without Robolectric or a device.
 */
internal const val CORE_COMMON_MARKER: String = "core-common"