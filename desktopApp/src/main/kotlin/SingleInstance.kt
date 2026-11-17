import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * One Wally per database file. The first process holds a lock on `<db path>.lock` and listens on a local
 * port (written to `<db path>.lock.port`); a second launch sends "show" to that port and exits.
 * The development database and the installed one have different paths, so they never block each other.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
) {
    fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val SHOW = "show"

        /**
         * Returns the instance when this process is the first one for [database]; calls [onShow] whenever a later
         * launch asks to show the window. Returns null when another process already runs (it has been asked to show).
         */
        fun acquire(database: File, onShow: () -> Unit): SingleInstance? {
            val canonical = database.absoluteFile.also { it.parentFile?.mkdirs() }.canonicalFile
            val lockFile = File(canonical.path + ".lock")
            val portFile = File(canonical.path + ".lock.port")
            val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                askToShow(portFile)
                return null
            }
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            val instance = SingleInstance(channel, lock, server)
            Thread({
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 2_000
                            val line = socket.getInputStream().bufferedReader().readLine()
                            if (line == SHOW) onShow()
                        }
                    } catch (e: Exception) {
                        // a broken client or the server closing: keep listening unless closed
                    }
                }
            }, "wally-show-listener").apply { isDaemon = true }.start()
            return instance
        }

        /** The first instance may still be writing its port: try for a few seconds. */
        private fun askToShow(portFile: File) {
            repeat(30) {
                val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                if (port != null) {
                    try {
                        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                            s.getOutputStream().write("$SHOW\n".toByteArray())
                            s.getOutputStream().flush()
                        }
                        return
                    } catch (e: Exception) {
                        // not listening yet (or a stale port): retry
                    }
                }
                Thread.sleep(100)
            }
        }
    }
}
