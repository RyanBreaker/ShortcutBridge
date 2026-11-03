package uichimevol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val TEST_TOKEN = "test-token-with-at-least-32-characters"
const val UPSTAIRS_ID = "upstairs-id"
const val DOWNSTAIRS_ID = "downstairs-id"

class FakeUniFiGateway(
    initialChimes: Map<String, JsonObject>,
) : UniFiGateway {
    private val chimes = initialChimes.toMutableMap()
    val patchCalls = mutableListOf<Pair<String, JsonArray>>()

    override suspend fun listChimes(): List<JsonObject> = chimes.values.toList()

    override suspend fun getChime(id: String): JsonObject =
        chimes[id] ?: throw UniFiRejectedException(404)

    override suspend fun patchChime(id: String, ringSettings: JsonArray) {
        val current = chimes[id] ?: throw UniFiRejectedException(404)
        patchCalls += id to ringSettings
        chimes[id] = JsonObject(current.toMutableMap().apply { put("ringSettings", ringSettings) })
    }
}

fun chime(
    id: String,
    volume: Int = 50,
    cameraId: String = "$id-camera",
    extraSetting: String = "preserve-me",
): JsonObject = buildJsonObject {
    put("id", id)
    put("name", id)
    putJsonArray("cameraIds") { add(kotlinx.serialization.json.JsonPrimitive(cameraId)) }
    putJsonArray("ringSettings") {
        add(buildJsonObject {
            put("cameraId", cameraId)
            put("volume", volume)
            put("repeatTimes", 2)
            put("ringtoneId", "default")
            put("futureField", extraSetting)
        })
    }
}

fun testVolumeService(uniFi: UniFiGateway): VolumeService = VolumeService(
    uniFi = uniFi,
    chimeIds = mapOf(Target.Upstairs to UPSTAIRS_ID, Target.Downstairs to DOWNSTAIRS_ID),
    presetVolumes = mapOf(Preset.Quiet to 0, Preset.Normal to 50),
)
