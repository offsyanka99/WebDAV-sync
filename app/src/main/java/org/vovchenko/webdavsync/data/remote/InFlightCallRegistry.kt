package org.vovchenko.webdavsync.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls blocked in a transfer, so cancel can abort the socket.
 * The account client's call [Job] is registered for the whole send, including a Digest retry.
 */
@Singleton
class InFlightCallRegistry @Inject constructor() {
    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    fun register(job: Job) {
        jobs.add(job)
    }

    fun unregister(job: Job) {
        jobs.remove(job)
    }

    fun cancelAll() {
        val reason = CancellationException("sync cancelled")
        jobs.forEach { job -> job.cancel(reason) }
    }
}
