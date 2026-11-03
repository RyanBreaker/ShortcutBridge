package uichimevol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory

class VolumeService(
    private val uniFi: UniFiGateway,
    private val chimeIds: Map<Target, String>,
    private val presetVolumes: Map<Preset, Int>,
) {
    private val logger = LoggerFactory.getLogger(VolumeService::class.java)
    private val locks = chimeIds.mapValues { Mutex() }

    suspend fun read(target: Target): VolumeResponse {
        val failures = mutableListOf<UniFiException>()
        val results = selectedChimes(target).map { (alias, id) ->
            locks.getValue(alias).withLock {
                try {
                    ChimeVolumeResult(
                        chime = alias.wireName,
                        chimeId = id,
                        volumes = volumes(uniFi.getChime(id)),
                    )
                } catch (exception: UniFiException) {
                    failures += exception
                    failedResult(alias, id, exception)
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw operationFailure("One or more chime volumes could not be read", results, failures, false)
        }
        return VolumeResponse(target = target.wireName, results = results)
    }

    suspend fun set(target: Target, preset: Preset): VolumeResponse {
        val desiredVolume = presetVolumes.getValue(preset)
        val failures = mutableListOf<UniFiException>()
        val results = selectedChimes(target).map { (alias, id) ->
            locks.getValue(alias).withLock {
                val startedAt = System.nanoTime()
                try {
                    val result = updateChime(alias, id, desiredVolume)
                    logger.info(
                        "Chime volume update: preset={} target={} chimeId={} status={} durationMs={}",
                        preset.wireName,
                        alias.wireName,
                        id,
                        result.status,
                        elapsedMillis(startedAt),
                    )
                    result
                } catch (exception: UniFiException) {
                    failures += exception
                    logger.warn(
                        "Chime volume update failed: preset={} target={} chimeId={} error={} durationMs={}",
                        preset.wireName,
                        alias.wireName,
                        id,
                        exception::class.simpleName,
                        elapsedMillis(startedAt),
                    )
                    failedResult(alias, id, exception)
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw operationFailure("One or more chime volumes could not be updated", results, failures, true)
        }
        return VolumeResponse(target = target.wireName, preset = preset.wireName, results = results)
    }

    private suspend fun updateChime(alias: Target, id: String, desiredVolume: Int): ChimeVolumeResult {
        val current = uniFi.getChime(id)
        val currentSettings = ringSettings(current)
        val currentVolumes = volumes(currentSettings)
        if (currentVolumes.all { it.volume == desiredVolume }) {
            return ChimeVolumeResult(alias.wireName, id, "unchanged", currentVolumes)
        }

        val updatedSettings = JsonArray(
            currentSettings.map { element ->
                val setting = element as? JsonObject ?: throw UniFiInvalidResponseException()
                JsonObject(setting.toMutableMap().apply { put("volume", JsonPrimitive(desiredVolume)) })
            },
        )
        uniFi.patchChime(id, updatedSettings)

        val verifiedSettings = ringSettings(uniFi.getChime(id))
        val verifiedVolumes = volumes(verifiedSettings)
        if (verifiedVolumes.map { it.cameraId } != currentVolumes.map { it.cameraId } ||
            verifiedVolumes.any { it.volume != desiredVolume }
        ) {
            throw UniFiVerificationException()
        }
        return ChimeVolumeResult(alias.wireName, id, "changed", verifiedVolumes)
    }

    private fun volumes(chime: JsonObject): List<DoorbellVolume> = volumes(ringSettings(chime))

    private fun volumes(settings: JsonArray): List<DoorbellVolume> = settings.map { element ->
        val setting = element as? JsonObject ?: throw UniFiInvalidResponseException()
        val cameraId = (setting["cameraId"] as? JsonPrimitive)?.contentOrNull
            ?: throw UniFiInvalidResponseException()
        val volume = (setting["volume"] as? JsonPrimitive)?.intOrNull
            ?: throw UniFiInvalidResponseException()
        DoorbellVolume(cameraId, volume)
    }

    private fun ringSettings(chime: JsonObject): JsonArray =
        chime["ringSettings"] as? JsonArray ?: throw UniFiInvalidResponseException()

    private fun selectedChimes(target: Target): List<Pair<Target, String>> =
        if (target == Target.All) {
            listOf(Target.Upstairs, Target.Downstairs).map { it to chimeIds.getValue(it) }
        } else {
            listOf(target to chimeIds.getValue(target))
        }

    private fun failedResult(alias: Target, id: String, exception: UniFiException) =
        ChimeVolumeResult(alias.wireName, id, "failed", error = exception.message)

    private fun operationFailure(
        message: String,
        results: List<ChimeVolumeResult>,
        failures: List<UniFiException>,
        mutation: Boolean,
    ): VolumeOperationException {
        val timedOut = failures.any { it is UniFiTimeoutException }
        val code = when {
            timedOut && mutation -> "unifi_outcome_unknown"
            timedOut -> "unifi_timeout"
            failures.any { it is UniFiVerificationException } -> "unifi_verification_failed"
            else -> "unifi_operation_failed"
        }
        return VolumeOperationException(code, message, results, timedOut)
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
