package com.odoocompanion.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

// Keep cancellation attached to the call until the response body has been consumed.
internal suspend fun <T> Call.readResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) {
                    response.close()
                    return
                }
                continuation.resumeWith(runCatching { response.use(read) })
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
        })
    }
