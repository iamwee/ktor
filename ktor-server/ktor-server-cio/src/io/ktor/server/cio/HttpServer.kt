package io.ktor.server.cio

import io.ktor.http.cio.*
import io.ktor.http.cio.internals.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.CancellationException
import java.io.*
import java.net.*
import java.nio.channels.*
import java.time.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

class HttpServer(val rootServerJob: Job, val serverSocket: Deferred<ServerSocket>) {
    companion object {
        val CancelledServer = HttpServer(rootServerJob = Job().apply { cancel() },
                serverSocket = CompletableDeferred<ServerSocket>().apply { completeExceptionally(java.util.concurrent.CancellationException()) })
    }
}

data class HttpServerSettings(
        val host: String = "0.0.0.0",
        val port: Int = 8080,
        val connectionIdleTimeoutSeconds: Long = 45
)

fun httpServer(settings: HttpServerSettings, callDispatcher: CoroutineContext = ioCoroutineDispatcher, handler: HttpRequestHandler): HttpServer {
    val socket = CompletableDeferred<ServerSocket>()

    val serverJob = launch(ioCoroutineDispatcher) {
        ActorSelectorManager(ioCoroutineDispatcher).use { selector ->
            aSocket(selector).tcp().bind(InetSocketAddress(settings.host, settings.port)).use { server ->
                socket.complete(server)

                val liveConnections = ConcurrentHashMap<Socket, Unit>()
                val timeout = WeakTimeoutQueue(TimeUnit.SECONDS.toMillis(settings.connectionIdleTimeoutSeconds), Clock.systemUTC(), { TimeoutCancellationException("Connection IDLE") })

                try {
                    while (true) {
                        val client = server.accept()
                        liveConnections.put(client, Unit)
                        client.closed.invokeOnCompletion {
                            liveConnections.remove(client)
                        }

                        try {
                            launch(ioCoroutineDispatcher) {
                                try {
                                    handleConnectionPipeline(client.openReadChannel(), client.openWriteChannel(true), ioCoroutineDispatcher, callDispatcher, timeout, handler)
                                } catch (io: IOException) {
                                } finally {
                                    client.close()
                                }
                            }
                        } catch (rejected: Throwable) {
                            client.close()
                        }
                    }
                } catch (cancelled: CancellationException) {
                } catch (closed: ClosedChannelException) {
                } finally {
                    server.close()
                    server.awaitClosed()
                    liveConnections.keys.forEach {
                        it.close()
                    }
                    while (liveConnections.isNotEmpty()) {
                        liveConnections.keys.forEach {
                            it.close()
                            it.awaitClosed()
                        }
                    }
                }
            }
        }
    }

    return HttpServer(serverJob, socket)
}
