package uichimevol

import java.net.URI

data class UniFiConnectionConfig(
    val apiKey: String,
    val consoleId: String,
    val baseUrl: String,
    val timeoutMillis: Long,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): UniFiConnectionConfig {
            val consoleId = environment.required("UNIFI_CONSOLE_ID")
            val baseUrl = environment["UNIFI_BASE_URL"]?.trim()?.trimEnd('/').orEmpty()
                .ifEmpty {
                    "https://api.ui.com/v1/connector/consoles/$consoleId/proxy/protect/integration"
                }
            val uri = runCatching { URI(baseUrl) }.getOrElse {
                throw IllegalArgumentException("UNIFI_BASE_URL must be a valid HTTPS URL")
            }
            require(uri.scheme == "https" && uri.host != null) {
                "UNIFI_BASE_URL must be a valid HTTPS URL"
            }
            return UniFiConnectionConfig(
                apiKey = environment.required("UNIFI_API_KEY"),
                consoleId = consoleId,
                baseUrl = baseUrl,
                timeoutMillis = environment.positiveLong("UNIFI_TIMEOUT_MS", 30_000),
            )
        }
    }
}

data class AppConfig(
    val serviceToken: String,
    val uniFi: UniFiConnectionConfig,
    val chimeIds: Map<Target, String>,
    val presetVolumes: Map<Preset, Int>,
    val port: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            val upstairsId = environment.required("CHIME_UPSTAIRS_ID")
            val downstairsId = environment.required("CHIME_DOWNSTAIRS_ID")
            require(upstairsId != downstairsId) { "Chime IDs must identify different devices" }

            return AppConfig(
                serviceToken = environment.required("SERVICE_BEARER_TOKEN").also {
                    require(it.length >= 32) { "SERVICE_BEARER_TOKEN must contain at least 32 characters" }
                },
                uniFi = UniFiConnectionConfig.fromEnvironment(environment),
                chimeIds = mapOf(Target.Upstairs to upstairsId, Target.Downstairs to downstairsId),
                presetVolumes = mapOf(
                    Preset.Quiet to environment.volume("VOLUME_QUIET", 0),
                    Preset.Normal to environment.volume("VOLUME_NORMAL", 50),
                ),
                port = environment.port(),
            )
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    this[name]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("$name is required")

private fun Map<String, String>.positiveLong(name: String, default: Long): Long {
    val raw = this[name]?.trim().orEmpty()
    val value = if (raw.isEmpty()) default else raw.toLongOrNull()
        ?: throw IllegalArgumentException("$name must be a whole number")
    require(value > 0) { "$name must be greater than zero" }
    return value
}

private fun Map<String, String>.volume(name: String, default: Int): Int {
    val raw = this[name]?.trim().orEmpty()
    val value = if (raw.isEmpty()) default else raw.toIntOrNull()
        ?: throw IllegalArgumentException("$name must be a whole number")
    require(value in 0..100) { "$name must be between 0 and 100" }
    return value
}

private fun Map<String, String>.port(): Int {
    val raw = this["PORT"]?.trim().orEmpty()
    val value = if (raw.isEmpty()) 8080 else raw.toIntOrNull()
        ?: throw IllegalArgumentException("PORT must be a whole number")
    require(value in 1..65535) { "PORT must be between 1 and 65535" }
    return value
}
