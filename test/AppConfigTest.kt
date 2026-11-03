package uichimevol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun loadsDefaultsAndTargets() {
        val config = AppConfig.fromEnvironment(validEnvironment())

        assertEquals(0, config.presetVolumes[Preset.Quiet])
        assertEquals(50, config.presetVolumes[Preset.Normal])
        assertEquals(30_000, config.uniFi.timeoutMillis)
        assertEquals(UPSTAIRS_ID, config.chimeIds[Target.Upstairs])
        assertEquals(
            "https://api.ui.com/v1/connector/consoles/console-id/proxy/protect/integration",
            config.uniFi.baseUrl,
        )
    }

    @Test
    fun discoveryConfigurationDoesNotRequireTargetsOrServiceToken() {
        val config = UniFiConnectionConfig.fromEnvironment(
            mapOf("UNIFI_API_KEY" to "api-key", "UNIFI_CONSOLE_ID" to "console-id"),
        )

        assertEquals("api-key", config.apiKey)
        assertEquals("console-id", config.consoleId)
    }

    @Test
    fun rejectsInvalidVolume() {
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(validEnvironment() + ("VOLUME_QUIET" to "101"))
        }

        assertEquals("VOLUME_QUIET must be between 0 and 100", error.message)
    }

    @Test
    fun rejectsNonnumericOptionalValuesInsteadOfUsingDefaults() {
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(validEnvironment() + ("VOLUME_NORMAL" to "loud"))
        }

        assertEquals("VOLUME_NORMAL must be a whole number", error.message)
    }

    @Test
    fun rejectsShortServiceToken() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(validEnvironment() + ("SERVICE_BEARER_TOKEN" to "short"))
        }
    }

    @Test
    fun rejectsDuplicateChimeIds() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(validEnvironment() + ("CHIME_DOWNSTAIRS_ID" to UPSTAIRS_ID))
        }
    }
}

private fun validEnvironment(): Map<String, String> = mapOf(
    "SERVICE_BEARER_TOKEN" to TEST_TOKEN,
    "UNIFI_API_KEY" to "api-key",
    "UNIFI_CONSOLE_ID" to "console-id",
    "CHIME_UPSTAIRS_ID" to UPSTAIRS_ID,
    "CHIME_DOWNSTAIRS_ID" to DOWNSTAIRS_ID,
)
