package com.safesignal

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Deliberately thin: SafeSignal performs **no work at startup**. In particular it
 * does not open the microphone, restore an armed state, or begin recording.
 *
 * That last point is a product requirement rather than an implementation detail.
 * The specification forbids recording from starting immediately after
 * installation, and a user who force-quits the app must not find that arming
 * silently resumes. Startup does exactly three things:
 *
 *  1. initialises Hilt,
 *  2. creates the notification channels (required before any FGS runs),
 *  3. runs filesystem/database reconciliation, which is read-only apart from
 *     discarding `.tmp` files that were never committed as evidence.
 *
 * Reconciliation is genuinely required at startup — it is what makes crash
 * recovery work — and it is safe because it never opens the microphone.
 */
@HiltAndroidApp
class SafeSignalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Intentionally minimal. See the class documentation for why no
        // microphone access or state restoration happens here.
    }
}