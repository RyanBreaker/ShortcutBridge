package uichimevol

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VolumeServiceTest {
    @Test
    fun quietUpdatesOnlyUpstairsAndPreservesOtherSettings() = runBlocking {
        val gateway = FakeUniFiGateway(twoChimes())
        val response = testVolumeService(gateway).set(Target.Upstairs, Preset.Quiet)

        assertEquals(1, gateway.patchCalls.size)
        val patched = gateway.patchCalls.single().second.single().jsonObject
        assertEquals(0, patched.getValue("volume").jsonPrimitive.int)
        assertEquals(2, patched.getValue("repeatTimes").jsonPrimitive.int)
        assertEquals("preserve-me", patched.getValue("futureField").jsonPrimitive.content)
        assertEquals("changed", response.results.single().status)
        assertEquals(50, gateway.getChime(DOWNSTAIRS_ID).volumes().single())
    }

    @Test
    fun unchangedVolumeSkipsPatch() = runBlocking {
        val gateway = FakeUniFiGateway(mapOf(UPSTAIRS_ID to chime(UPSTAIRS_ID, volume = 0)))

        val response = testVolumeService(gateway).set(Target.Upstairs, Preset.Quiet)

        assertEquals("unchanged", response.results.single().status)
        assertTrue(gateway.patchCalls.isEmpty())
    }

    @Test
    fun allAttemptsBothChimesAndReportsPartialFailure() = runBlocking {
        val base = FakeUniFiGateway(twoChimes())
        val gateway = object : UniFiGateway by base {
            override suspend fun getChime(id: String): JsonObject {
                if (id == DOWNSTAIRS_ID) throw UniFiRejectedException(503)
                return base.getChime(id)
            }
        }

        val error = assertFailsWith<VolumeOperationException> {
            testVolumeService(gateway).set(Target.All, Preset.Quiet)
        }

        assertEquals(listOf("changed", "failed"), error.results.map { it.status })
        assertEquals(1, base.patchCalls.size)
        assertEquals("unifi_operation_failed", error.code)
    }

    @Test
    fun rejectsUnverifiedUpdate() = runBlocking {
        val base = FakeUniFiGateway(twoChimes())
        var reads = 0
        val gateway = object : UniFiGateway by base {
            override suspend fun getChime(id: String): JsonObject {
                reads += 1
                return if (reads == 1) base.getChime(id) else chime(id, volume = 50)
            }
        }

        val error = assertFailsWith<VolumeOperationException> {
            testVolumeService(gateway).set(Target.Upstairs, Preset.Quiet)
        }

        assertEquals("unifi_verification_failed", error.code)
        assertEquals("failed", error.results.single().status)
    }

    @Test
    fun updatesEveryDoorbellPairing() = runBlocking {
        val original = chime(UPSTAIRS_ID)
        val firstSetting = original.getValue("ringSettings").jsonArray.single()
        val secondSetting = buildJsonObject {
            put("cameraId", "second-camera")
            put("volume", 70)
            put("repeatTimes", 4)
            put("ringtoneId", "custom")
            put("futureField", "also-preserved")
        }
        val multi = JsonObject(
            original.toMutableMap().apply {
                put("ringSettings", JsonArray(listOf(firstSetting, secondSetting)))
            },
        )
        val gateway = FakeUniFiGateway(mapOf(UPSTAIRS_ID to multi))

        val response = testVolumeService(gateway).set(Target.Upstairs, Preset.Quiet)

        assertEquals(listOf(0, 0), response.results.single().volumes.map { it.volume })
        val patchedSettings = gateway.patchCalls.single().second
        assertEquals(4, patchedSettings[1].jsonObject.getValue("repeatTimes").jsonPrimitive.int)
        assertEquals("also-preserved", patchedSettings[1].jsonObject.getValue("futureField").jsonPrimitive.content)
    }

    @Test
    fun serializesMutationsForTheSameChime() = runBlocking {
        val base = FakeUniFiGateway(twoChimes())
        val activePatches = AtomicInteger()
        val maximumActivePatches = AtomicInteger()
        val gateway = object : UniFiGateway by base {
            override suspend fun patchChime(id: String, ringSettings: JsonArray) {
                val active = activePatches.incrementAndGet()
                maximumActivePatches.updateAndGet { previous -> maxOf(previous, active) }
                try {
                    delay(20)
                    base.patchChime(id, ringSettings)
                } finally {
                    activePatches.decrementAndGet()
                }
            }
        }
        val service = testVolumeService(gateway)

        val first = async { service.set(Target.Upstairs, Preset.Quiet) }
        val second = async { service.set(Target.Upstairs, Preset.Normal) }
        first.await()
        second.await()

        assertEquals(1, maximumActivePatches.get())
        assertEquals(2, base.patchCalls.size)
    }
}

private fun twoChimes(): Map<String, JsonObject> = mapOf(
    UPSTAIRS_ID to chime(UPSTAIRS_ID),
    DOWNSTAIRS_ID to chime(DOWNSTAIRS_ID),
)

private fun JsonObject.volumes(): List<Int> =
    getValue("ringSettings").jsonArray.map { it.jsonObject.getValue("volume").jsonPrimitive.int }
