package uichimevol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.IOException

interface UniFiGateway {
    suspend fun listChimes(): List<JsonObject>
    suspend fun getChime(id: String): JsonObject
    suspend fun patchChime(id: String, ringSettings: JsonArray)
}

sealed class UniFiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UniFiTimeoutException(cause: Throwable) :
    UniFiException("UniFi did not respond before the request timed out", cause)

class UniFiUnavailableException(cause: Throwable) :
    UniFiException("UniFi could not be reached", cause)

class UniFiRejectedException(val upstreamStatus: Int) :
    UniFiException("UniFi rejected the request with HTTP $upstreamStatus")

class UniFiInvalidResponseException(cause: Throwable? = null) :
    UniFiException("UniFi returned an unexpected response", cause)

class UniFiVerificationException :
    UniFiException("UniFi did not confirm the requested volume")

class UniFiClient(
    private val client: HttpClient,
    private val config: UniFiConnectionConfig,
) : UniFiGateway, Closeable {
    companion object {
        fun create(config: UniFiConnectionConfig): UniFiClient {
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = config.timeoutMillis
                    connectTimeoutMillis = config.timeoutMillis
                    socketTimeoutMillis = config.timeoutMillis
                }
            }
            return UniFiClient(client, config)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listChimes(): List<JsonObject> = execute {
        client.get("${config.baseUrl}/v1/chimes") { addUniFiCredentials() }
    }
        .let { it as? JsonArray ?: throw UniFiInvalidResponseException() }
        .map { it as? JsonObject ?: throw UniFiInvalidResponseException() }

    override suspend fun getChime(id: String): JsonObject = execute {
        client.get("${config.baseUrl}/v1/chimes/${id.encodeURLPathPart()}") { addUniFiCredentials() }
    } as? JsonObject ?: throw UniFiInvalidResponseException()

    override suspend fun patchChime(id: String, ringSettings: JsonArray) {
        execute {
            client.patch("${config.baseUrl}/v1/chimes/${id.encodeURLPathPart()}") {
                addUniFiCredentials()
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("ringSettings" to ringSettings)).toString())
            }
        }
    }

    private fun HttpRequestBuilder.addUniFiCredentials() {
        header("X-API-KEY", config.apiKey)
        accept(ContentType.Application.Json)
    }

    private suspend fun execute(request: suspend () -> HttpResponse): kotlinx.serialization.json.JsonElement {
        try {
            val response = request()
            if (!response.status.isSuccess()) {
                response.bodyAsText()
                throw UniFiRejectedException(response.status.value)
            }
            return json.parseToJsonElement(response.bodyAsText())
        } catch (exception: UniFiException) {
            throw exception
        } catch (exception: HttpRequestTimeoutException) {
            throw UniFiTimeoutException(exception)
        } catch (exception: ConnectTimeoutException) {
            throw UniFiTimeoutException(exception)
        } catch (exception: SocketTimeoutException) {
            throw UniFiTimeoutException(exception)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: kotlinx.io.IOException) {
            throw UniFiUnavailableException(exception)
        } catch (exception: IOException) {
            throw UniFiUnavailableException(exception)
        } catch (exception: Exception) {
            throw UniFiInvalidResponseException(exception)
        }
    }

    override fun close() = client.close()
}
