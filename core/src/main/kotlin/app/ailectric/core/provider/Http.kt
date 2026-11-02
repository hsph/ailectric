package app.ailectric.core.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object Http {
    /** Both OSM and OCM require a custom user agent that identifies the app. */
    const val USER_AGENT = "Ailectric/0.1 (private EV charging finder; Android)"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
}

internal suspend fun OkHttpClient.fetchBody(request: Request, source: String): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw ProviderException("$source nicht erreichbar", e)
    }
    return withContext(Dispatchers.IO) {
        response.use {
            when {
                it.isSuccessful -> it.body.string()
                it.code == 401 || it.code == 403 ->
                    throw ProviderException("$source: API-Schlüssel fehlt oder ist ungültig", retryable = false)
                it.code == 429 || it.code == 406 ->
                    throw ProviderException("$source: Abfragelimit erreicht, bitte später erneut versuchen")
                else -> throw ProviderException("$source: HTTP ${it.code}")
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) =
            cont.resume(response) { _, value, _ -> value.close() }
    })
}
