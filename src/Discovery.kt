package uichimevol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class ChimeDiscovery(private val uniFi: UniFiGateway) {
    suspend fun discover(): DiscoveryResponse = DiscoveryResponse(
        uniFi.listChimes().map(::discoveredChime).sortedWith(compareBy({ it.name.orEmpty() }, { it.id })),
    )

    private fun discoveredChime(chime: JsonObject): DiscoveryChime {
        val id = chime["id"].stringValue()
        val name = chime["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val settings = chime["ringSettings"] as? JsonArray ?: throw UniFiInvalidResponseException()
        val doorbells = settings.map { element ->
            val setting = element as? JsonObject ?: throw UniFiInvalidResponseException()
            DoorbellVolume(
                cameraId = setting["cameraId"].stringValue(),
                volume = (setting["volume"] as? JsonPrimitive)?.intOrNull
                    ?: throw UniFiInvalidResponseException(),
            )
        }
        return DiscoveryChime(id, name, doorbells)
    }

    private fun kotlinx.serialization.json.JsonElement?.stringValue(): String =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw UniFiInvalidResponseException()
}
