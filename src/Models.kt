package uichimevol

import kotlinx.serialization.Serializable

enum class Target(val wireName: String) {
    Upstairs("upstairs"),
    Downstairs("downstairs"),
    All("all"),
    ;

    companion object {
        fun parse(value: String): Target? = entries.singleOrNull { it.wireName == value }
    }
}

enum class Preset(val wireName: String) {
    Quiet("quiet"),
    Normal("normal"),
    ;

    companion object {
        fun parse(value: String): Preset? = entries.singleOrNull { it.wireName == value }
    }
}

@Serializable
data class VolumeRequest(
    val preset: String,
    val target: String = "all",
)

@Serializable
data class DoorbellVolume(
    val cameraId: String,
    val volume: Int,
)

@Serializable
data class ChimeVolumeResult(
    val chime: String,
    val chimeId: String,
    val status: String? = null,
    val volumes: List<DoorbellVolume> = emptyList(),
    val error: String? = null,
)

@Serializable
data class VolumeResponse(
    val target: String,
    val preset: String? = null,
    val results: List<ChimeVolumeResult>,
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail,
    val results: List<ChimeVolumeResult>? = null,
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
)

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class DiscoveryResponse(val chimes: List<DiscoveryChime>)

@Serializable
data class DiscoveryChime(
    val id: String,
    val name: String?,
    val doorbells: List<DoorbellVolume>,
)

class ApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
) : Exception(message)

class VolumeOperationException(
    val code: String,
    override val message: String,
    val results: List<ChimeVolumeResult>,
    val timedOut: Boolean,
) : Exception(message)
