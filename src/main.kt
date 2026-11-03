package uichimevol

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when {
        args.isEmpty() -> runService()
        args.contentEquals(arrayOf("discover-chimes")) -> runDiscovery()
        else -> {
            System.err.println("Unknown command. Use discover-chimes or no command to run the service.")
            exitProcess(1)
        }
    }
}

private fun runService() {
    val config = AppConfig.fromEnvironment()
    UniFiClient.create(config.uniFi).use { uniFi ->
        val volumeService = VolumeService(uniFi, config.chimeIds, config.presetVolumes)
        embeddedServer(Netty, port = config.port) {
            serviceModule(volumeService, config.serviceToken)
        }.start(wait = true)
    }
}

private fun runDiscovery() {
    val config = UniFiConnectionConfig.fromEnvironment()
    UniFiClient.create(config).use { uniFi ->
        val result = runBlocking { ChimeDiscovery(uniFi).discover() }
        println(Json.encodeToString(result))
    }
}
