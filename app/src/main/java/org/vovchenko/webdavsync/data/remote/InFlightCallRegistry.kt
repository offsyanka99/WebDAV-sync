package org.vovchenko.webdavsync.data.remote

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** OkHttp calls currently blocked in `execute()`, so cancel can abort the socket. */
@Singleton
class InFlightCallRegistry @Inject constructor() {
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    fun register(call: Call) {
        calls.add(call)
    }

    fun unregister(call: Call) {
        calls.remove(call)
    }

    fun cancelAll() {
        calls.forEach { call -> call.cancel() }
    }
}
