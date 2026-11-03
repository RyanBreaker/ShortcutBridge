package uichimevol

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.security.MessageDigest

fun Application.serviceModule(volumeService: VolumeService, serviceToken: String) {
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, exception ->
            call.respondError(exception.statusCode, exception.code, exception.message)
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(
                HttpStatusCode.BadRequest.value,
                "invalid_request",
                "Request body must be valid JSON containing preset and optional target",
            )
        }
        exception<VolumeOperationException> { call, exception ->
            val status = if (exception.timedOut) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway
            call.respond(
                status,
                ErrorResponse(
                    error = ErrorDetail(exception.code, exception.message),
                    results = exception.results,
                ),
            )
        }
        exception<Throwable> { call, exception ->
            call.application.log.error("Unhandled request failure", exception)
            call.respondError(
                HttpStatusCode.InternalServerError.value,
                "internal_error",
                "The service could not complete the request",
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound.value, "not_found", "Endpoint not found")
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        get("/v1/chimes/volume") {
            if (!call.isAuthorized(serviceToken)) return@get
            val target = call.request.queryParameters["target"]?.let(::parseTarget) ?: Target.All
            call.respond(volumeService.read(target))
        }
        put("/v1/chimes/volume") {
            if (!call.isAuthorized(serviceToken)) return@put
            val request = call.receive<VolumeRequest>()
            val preset = Preset.parse(request.preset)
                ?: throw ApiException(400, "invalid_preset", "Preset must be quiet or normal")
            val target = parseTarget(request.target)
            call.respond(volumeService.set(target, preset))
        }
    }
}

private fun parseTarget(value: String): Target =
    Target.parse(value) ?: throw ApiException(
        400,
        "invalid_target",
        "Target must be upstairs, downstairs, or all",
    )

private suspend fun io.ktor.server.application.ApplicationCall.isAuthorized(expectedToken: String): Boolean {
    val header = request.headers[HttpHeaders.Authorization]
    val suppliedToken = header
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        .orEmpty()
    val matches = MessageDigest.isEqual(
        expectedToken.toByteArray(Charsets.UTF_8),
        suppliedToken.toByteArray(Charsets.UTF_8),
    )
    if (!matches) {
        respondError(401, "unauthorized", "A valid bearer token is required")
    }
    return matches
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    statusCode: Int,
    code: String,
    message: String,
) {
    respond(HttpStatusCode.fromValue(statusCode), ErrorResponse(ErrorDetail(code, message)))
}
