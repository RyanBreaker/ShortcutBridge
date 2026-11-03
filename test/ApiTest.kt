package uichimevol

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApiTest {
    @Test
    fun volumeEndpointsRequireBearerAuthentication() = testApi { responseClient ->
        val getResponse = responseClient.get("/v1/chimes/volume")
        val putResponse = responseClient.put("/v1/chimes/volume") {
            contentType(ContentType.Application.Json)
            setBody("""{"preset":"quiet"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, getResponse.status)
        assertEquals("unauthorized", getResponse.error().error.code)
        assertEquals(HttpStatusCode.Unauthorized, putResponse.status)
        assertEquals("unauthorized", putResponse.error().error.code)
    }

    @Test
    fun putDefaultsToAllAndReturnsPerChimeResults() = testApi { client ->
        val response = client.put("/v1/chimes/volume") {
            authorized()
            contentType(ContentType.Application.Json)
            setBody("""{"preset":"normal"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<VolumeResponse>(response.bodyAsText())
        assertEquals("all", body.target)
        assertEquals(listOf("upstairs", "downstairs"), body.results.map { it.chime })
        assertEquals(listOf("unchanged", "unchanged"), body.results.map { it.status })
    }

    @Test
    fun getSupportsSingleChimeTarget() = testApi { client ->
        val response = client.get("/v1/chimes/volume?target=upstairs") { authorized() }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<VolumeResponse>(response.bodyAsText())
        assertEquals("upstairs", body.target)
        assertEquals(UPSTAIRS_ID, body.results.single().chimeId)
        assertFalse(response.bodyAsText().contains("preset"))
    }

    @Test
    fun rejectsInvalidPresetTargetAndMalformedJson() = testApi { client ->
        val invalidPreset = client.put("/v1/chimes/volume") {
            authorized()
            contentType(ContentType.Application.Json)
            setBody("""{"preset":"silent"}""")
        }
        val invalidTarget = client.get("/v1/chimes/volume?target=garage") { authorized() }
        val malformed = client.put("/v1/chimes/volume") {
            authorized()
            contentType(ContentType.Application.Json)
            setBody("{not-json")
        }

        assertEquals("invalid_preset", invalidPreset.error().error.code)
        assertEquals("invalid_target", invalidTarget.error().error.code)
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("invalid_request", malformed.error().error.code)
    }

    @Test
    fun mutationTimeoutReturnsUnknownOutcomeAndFailedResult() = testApplication {
        val failingGateway = object : UniFiGateway {
            override suspend fun listChimes(): List<JsonObject> = emptyList()
            override suspend fun getChime(id: String): JsonObject =
                throw UniFiTimeoutException(IOException("timed out"))
            override suspend fun patchChime(id: String, ringSettings: kotlinx.serialization.json.JsonArray) = Unit
        }
        application { serviceModule(testVolumeService(failingGateway), TEST_TOKEN) }

        val response = client.put("/v1/chimes/volume") {
            authorized()
            contentType(ContentType.Application.Json)
            setBody("""{"preset":"quiet","target":"upstairs"}""")
        }

        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("unifi_outcome_unknown", body.error.code)
        assertEquals("failed", body.results?.single()?.status)
    }
}

private fun testApi(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) = testApplication {
    val gateway = FakeUniFiGateway(
        mapOf(
            UPSTAIRS_ID to chime(UPSTAIRS_ID),
            DOWNSTAIRS_ID to chime(DOWNSTAIRS_ID),
        ),
    )
    application { serviceModule(testVolumeService(gateway), TEST_TOKEN) }
    block(client)
}

private fun io.ktor.client.request.HttpRequestBuilder.authorized() {
    header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
}

private suspend fun HttpResponse.error(): ErrorResponse =
    Json.decodeFromString<ErrorResponse>(bodyAsText())
