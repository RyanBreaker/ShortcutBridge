package uichimevol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryTest {
    @Test
    fun discoveryListsIdsNamesAndDoorbellVolumesWithoutPatching() = runBlocking {
        val gateway = FakeUniFiGateway(
            mapOf(
                UPSTAIRS_ID to chime(UPSTAIRS_ID),
                DOWNSTAIRS_ID to chime(DOWNSTAIRS_ID),
            ),
        )

        val result = ChimeDiscovery(gateway).discover()

        assertEquals(2, result.chimes.size)
        assertEquals(DOWNSTAIRS_ID, result.chimes[0].id)
        assertEquals(50, result.chimes[0].doorbells.single().volume)
        assertEquals("$DOWNSTAIRS_ID-camera", result.chimes[0].doorbells.single().cameraId)
        assertEquals(0, gateway.patchCalls.size)
    }
}
