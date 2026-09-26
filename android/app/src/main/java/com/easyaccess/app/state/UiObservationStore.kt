package com.easyaccess.app.state

import com.easyaccess.app.model.UiObservation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory only. The first prototype deliberately does not persist UI content.
 */
object UiObservationStore {
    private val listeners = CopyOnWriteArrayList<(UiObservation?) -> Unit>()

    @Volatile
    var latest: UiObservation? = null
        private set

    fun publish(observation: UiObservation?) {
        latest = observation
        listeners.forEach { it(observation) }
    }

    fun subscribe(listener: (UiObservation?) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun unsubscribe(listener: (UiObservation?) -> Unit) {
        listeners -= listener
    }

    fun clear() = publish(null)
}
