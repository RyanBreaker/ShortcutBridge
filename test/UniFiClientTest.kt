package uichimevol

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UniFiClientTest {
    @Test
    fun sendsApiKeyAndReadOnlyChimePath() = runBlocking {
        var apiKey: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            apiKey = request.headers["X-API-KEY"]
            path = request.url.encodedPath
            respond(chime(UPSTAIRS_ID).toString(), headers = jsonHeaders())
        }
        val client = UniFiClient(HttpClient(engine), connectionConfig())

        val result = client.getChime(UPSTAIRS_ID)

        assertEquals("test-api-key", apiKey)
        assertEquals("/protect/v1/chimes/$UPSTAIRS_ID", path)
        assertEquals(UPSTAIRS_ID, result.getValue("id").jsonPrimitive.content)
        client.close()
    }

    @Test
    fun patchContainsOnlyRingSettings() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("{}", headers = jsonHeaders())
        }
        val client = UniFiClient(HttpClient(engine), connectionConfig())
        val settings = chime(UPSTAIRS_ID).getValue("ringSettings") as JsonArray

        client.patchChime(UPSTAIRS_ID, settings)

        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("ringSettings"), json.keys)
        assertEquals(50, (json.getValue("ringSettings") as JsonArray)[0].jsonObject.getValue("volume").jsonPrimitive.int)
        client.close()
    }

    @Test
    fun mapsUpstreamRejection() = runBlocking {
        val client = UniFiClient(
            HttpClient(MockEngine { respond("denied", status = HttpStatusCode.Forbidden) }),
            connectionConfig(),
        )

        val error = assertFailsWith<UniFiRejectedException> { client.getChime(UPSTAIRS_ID) }

        assertEquals(403, error.upstreamStatus)
        client.close()
    }

    @Test
    fun mapsRequestTimeout() = runBlocking {
        val engine = MockEngine {
            delay(100)
            respond("{}", headers = jsonHeaders())
        }
        val httpClient = HttpClient(engine) {
            install(HttpTimeout) { requestTimeoutMillis = 10 }
        }
        val client = UniFiClient(httpClient, connectionConfig())

        assertFailsWith<UniFiTimeoutException> { client.getChime(UPSTAIRS_ID) }

        client.close()
    }
}

private fun connectionConfig() = UniFiConnectionConfig(
    apiKey = "test-api-key",
    consoleId = "console-id",
    baseUrl = "https://example.test/protect",
    timeoutMillis = 30_000,
)

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
