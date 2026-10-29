package com.izzy2lost.nin64

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Local multiplayer for up to four players on the same Wi-Fi network.
 *
 * Every device boots the same ROM from power-on with the host's save memory,
 * emulation options and cheats, then runs in lockstep on identical inputs: the
 * core is deterministic, so the games stay identical, and a periodic RDRAM
 * checksum catches it if they ever drift.
 *
 * The host is the hub. Each guest sends its own controller input a few frames
 * ahead ([inputDelayFor]); the host combines everyone's input for a frame once
 * it has all of it and sends the complete frames back to every guest. Nobody
 * runs a frame before its complete inputs are known. A guest who leaves is
 * idle from a frame the host chooses, so the others cannot disagree about it.
 *
 * The host announces itself with a UDP broadcast ([DISCOVERY_PORT]); guests
 * join its lobby over TCP ([SESSION_PORT]), which then only carries lobby
 * news and departures. Inputs travel over UDP on the same port.
 */
internal object Netplay {
    const val DISCOVERY_PORT = 45064
    const val SESSION_PORT = 45065
    const val PROTOCOL_VERSION = 2
    const val MAX_PLAYERS = 4
    private const val TAG = "Nin64Netplay"
    private const val MAGIC = 0x4E363450 // "N64P"

    // UDP packet types.
    private const val PACKET_INPUT: Byte = 1    // guest -> host: its own inputs
    private const val PACKET_FRAMES: Byte = 2   // host -> guests: complete frames
    private const val PACKET_BYE: Byte = 3      // either way: leaving now (TCP's close can be slow to arrive)

    // TCP messages from the host, once a guest is in the lobby.
    private const val MESSAGE_LOBBY = 1
    private const val MESSAGE_START = 2
    private const val MESSAGE_LEFT = 3

    /** Frames of input delay: a third or fourth player's input takes two hops (guest, host, guest). */
    fun inputDelayFor(players: Int) = if (players <= 2) 3 else 4

    /**
     * Core options every player must run with. GLideN64's copies of rendered
     * pixels back into RDRAM depend on the GPU, so they would make the devices'
     * games differ; they are switched off for multiplayer.
     */
    val deterministicOptions = listOf(
        "mupen64plus-EnableCopyColorToRDRAM" to "Off",
        "mupen64plus-EnableCopyAuxToRDRAM" to "False",
        "mupen64plus-ThreadedRenderer" to "False",
    )

    /** What a host announces, and what a guest needs to find the same game. */
    data class Beacon(
        val title: String,
        val md5: String,
        val device: String,
        val players: Int,
        val address: InetAddress,
        val port: Int,
    )

    /** Everything a guest takes from the host before it boots. */
    class SessionConfig(
        val options: List<Pair<String, String>>,
        val cheats: List<String>,
        val saveMemory: ByteArray,
    )

    class NetplayException(message: String) : IOException(message)

    fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Nin64"

    // ── Wi-Fi ────────────────────────────────────────────────────────────────

    /** Keeps Wi-Fi out of power saving, whose wake-ups add 100 ms+ spikes to every packet. */
    class WifiLocks(context: Context) {
        private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        private val lowLatency = runCatching {
            @Suppress("DEPRECATION")
            wifi.createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Nin64:netplay"
            ).apply { setReferenceCounted(false) }
        }.getOrNull()
        // Some devices drop broadcast packets unless a multicast lock is held.
        private val multicast = runCatching {
            wifi.createMulticastLock("Nin64:discovery").apply { setReferenceCounted(false) }
        }.getOrNull()

        fun acquire() {
            runCatching { lowLatency?.acquire() }
            runCatching { multicast?.acquire() }
        }

        fun release() {
            runCatching { if (lowLatency?.isHeld == true) lowLatency.release() }
            runCatching { if (multicast?.isHeld == true) multicast.release() }
        }
    }

    /** Every IPv4 broadcast address of the connected networks, plus the limited broadcast. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    address.broadcast?.let(out::add)
                }
            }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.distinct()
    }

    /** This device's IPv4 address on the local network, to show the host. */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Announces a hosted game about once a second until [close]; [players] is read each time. */
    class Announcer(private val title: String, private val md5: String, private val players: () -> Int) {
        @Volatile private var open = true
        private val socket = DatagramSocket().apply { broadcast = true }
        private val thread = Thread({ run() }, "Nin64-NetAnnounce").apply { isDaemon = true; start() }

        private fun run() {
            while (open) {
                val payload = JSONObject()
                    .put("app", "nin64")
                    .put("v", PROTOCOL_VERSION)
                    .put("title", title)
                    .put("md5", md5)
                    .put("device", deviceName())
                    .put("players", players())
                    .put("port", SESSION_PORT)
                    .toString()
                    .toByteArray()
                for (address in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT)) }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }

        fun close() {
            open = false
            thread.interrupt()
            socket.close()
        }
    }

    /** Listens for hosts; [onChange] gets the current list, on the scanner's thread. */
    class Scanner(private val onChange: (List<Beacon>) -> Unit) {
        @Volatile private var open = true
        private val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 500
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        private val seen = LinkedHashMap<String, Pair<Beacon, Long>>()
        private val thread = Thread({ run() }, "Nin64-NetScan").apply { isDaemon = true; start() }

        private fun run() {
            val buffer = ByteArray(2048)
            while (open) {
                var changed = false
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length))
                    if (json.optString("app") == "nin64" && json.optInt("v") == PROTOCOL_VERSION) {
                        val beacon = Beacon(
                            title = json.optString("title"),
                            md5 = json.optString("md5"),
                            device = json.optString("device"),
                            players = json.optInt("players", 1),
                            address = packet.address,
                            port = json.optInt("port", SESSION_PORT),
                        )
                        val key = "${beacon.address.hostAddress}:${beacon.port}"
                        changed = seen.put(key, beacon to System.currentTimeMillis())?.first != beacon
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (!open) break
                }
                // A host that has gone quiet for a few seconds is gone (started its game, or left).
                val now = System.currentTimeMillis()
                changed = seen.entries.removeAll { now - it.value.second > 4000 } || changed
                if (changed && open) onChange(seen.values.map { it.first })
            }
        }

        fun close() {
            open = false
            socket.close()
        }
    }

    // ── The running session ──────────────────────────────────────────────────

    /** One device's view of a running game, called once per frame on the emulation thread. */
    interface Session {
        /** 0 = player 1 (the host). */
        val localPlayer: Int
        val playerCount: Int
        val closed: Boolean
        val closeReason: String?
        /** The first frame whose checksum differed from another player's, or -1. */
        val desyncedAtFrame: Int

        /**
         * Hands in this device's input and returns the packed inputs of all four
         * controllers for [frame], blocking until they are known. [onStall] gets
         * who is being waited for once waiting starts to show, and null when it
         * ends. Returns null once the session is over.
         */
        fun exchange(frame: Int, localInput: Int, onStall: (String?) -> Unit): IntArray?

        /** While paused: keeps the others from timing out. */
        fun idle()

        /** After running [frame]: compared with the other players' checksums for the same frame. */
        fun recordChecksum(frame: Int, checksum: Int)

        /** Things to tell the player ("X left the game"), each once. */
        fun takeEvents(): List<String>

        fun close(reason: String? = null)
    }

    /** Buttons in the low 16 bits, then the stick's X and Y (-128..127) a byte each. */
    fun pack(mask: Int, stickX: Int, stickY: Int): Int =
        (mask and 0xffff) or ((stickX and 0xff) shl 16) or ((stickY and 0xff) shl 24)

    private val NEUTRAL = pack(0, 0, 0)
    const val CHECKSUM_INTERVAL = 120
    private const val RING = 256
    private const val WINDOW = 32
    /** A paused player still sends every 100 ms, so this much silence means they are gone. */
    private const val TIMEOUT_MS = 8_000L
    private const val STALL_NOTICE_MS = 600L

    /** Checksums a device took, and which other player's disagreed first. */
    private class ChecksumBook {
        private val ours = HashMap<Int, Int>()
        var lastFrame = -1
            private set
        var lastValue = 0
            private set
        @Volatile var desyncedAtFrame = -1
            private set

        fun record(frame: Int, checksum: Int) {
            ours[frame] = checksum
            ours.keys.removeAll { it < frame - 10 * CHECKSUM_INTERVAL }
            lastFrame = frame
            lastValue = checksum
        }

        fun compare(frame: Int, theirs: Int, who: String) {
            if (frame < 0 || desyncedAtFrame >= 0) return
            val mine = ours[frame] ?: return
            if (mine != theirs) {
                desyncedAtFrame = frame
                Log.w(TAG, "desync with $who at frame $frame: ours=%08x theirs=%08x".format(mine, theirs))
            }
        }
    }

    /** For the log: frame rate, and how much of it went to waiting on the others. */
    private class Stats {
        private var startMs = System.currentTimeMillis()
        var waitedMs = 0L
        /** On the host: how long it waited on each player in particular. */
        val waitedOn = LinkedHashMap<String, Long>()

        fun log(frame: Int, checksum: Int) {
            if (frame % (CHECKSUM_INTERVAL * 5) != 0) return
            val now = System.currentTimeMillis()
            val fps = CHECKSUM_INTERVAL * 5 * 1000f / (now - startMs).coerceAtLeast(1)
            val on = if (waitedOn.isEmpty()) "" else waitedOn.entries.joinToString(prefix = " (", postfix = ")") { "${it.key} ${it.value}" }
            Log.i(TAG, "frame $frame checksum %08x fps %.1f waited %d ms%s".format(checksum, fps, waitedMs, on))
            startMs = now
            waitedMs = 0
            waitedOn.clear()
        }
    }

    /** A few copies, since any one datagram can be lost. */
    private fun sendBye(udp: DatagramSocket, to: List<InetSocketAddress>, slot: Int) {
        val bye = ByteBuffer.allocate(6).putInt(MAGIC).put(PACKET_BYE).put(slot.toByte()).array()
        repeat(3) {
            for (address in to) runCatching { udp.send(DatagramPacket(bye, bye.size, address)) }
        }
    }

    private fun DataOutputStream.writeNames(names: List<String>) {
        writeInt(names.size)
        names.forEach(::writeUTF)
    }

    private fun DataInputStream.readNames(): List<String> = List(readInt()) { readUTF() }

    // ── Host: lobby ──────────────────────────────────────────────────────────

    /** A player in the host's lobby, as the host's screen lists them. */
    data class LobbyPlayer(val name: String, val ready: Boolean)

    /**
     * Hosting, after boot and before the first frame. Guests join until the
     * host starts the game or the lobby is full; each takes [config], boots,
     * and reports ready. [onChange] is called, in order, on a network thread
     * whenever the roster changes; read [roster] for what it is now.
     */
    class HostLobby(
        title: String,
        private val md5: String,
        private val config: SessionConfig,
        private val onChange: () -> Unit,
    ) {
        private inner class Guest(
            val socket: Socket,
            val input: DataInputStream,
            val output: DataOutputStream,
            val name: String,
            val udpPort: Int,
        ) {
            @Volatile var ready = false
            /** Set once the game starts: where departures are reported from then on. */
            @Volatile var onLeft: (() -> Unit)? = null

            fun send(write: DataOutputStream.() -> Unit) {
                synchronized(this) {
                    runCatching {
                        output.write()
                        output.flush()
                    }
                }
            }
        }

        private val guests = mutableListOf<Guest>()
        private val rosterLock = Any()
        private val server = ServerSocket().apply {
            reuseAddress = true
            soTimeout = 500
            bind(InetSocketAddress(SESSION_PORT))
        }
        private val udp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(SESSION_PORT)) }
        private val announcer = Announcer(title, md5) { synchronized(guests) { guests.size } + 1 }
        @Volatile private var accepting = true
        @Volatile private var startRequested = false
        private val acceptThread = Thread({ acceptLoop() }, "Nin64-NetLobby").apply { isDaemon = true; start() }

        init {
            onChange()
        }

        fun roster(): List<LobbyPlayer> = synchronized(guests) {
            listOf(LobbyPlayer(deviceName(), true)) + guests.map { LobbyPlayer(it.name, it.ready) }
        }

        /** Whether [requestStart] would start now: at least one guest, all of them booted. */
        fun canStart(): Boolean = synchronized(guests) { guests.isNotEmpty() && guests.all { it.ready } }

        fun requestStart() {
            startRequested = true
        }

        private fun acceptLoop() {
            while (accepting) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                Thread({ admit(socket) }, "Nin64-NetGuest").apply { isDaemon = true; start() }
            }
        }

        private fun admit(socket: Socket) {
            var guest: Guest? = null
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())
                if (input.readInt() != MAGIC || input.readInt() != PROTOCOL_VERSION) throw NetplayException("not a Nin64 guest")
                val name = input.readUTF()
                val guestMd5 = input.readUTF()
                val udpPort = input.readInt()
                val refusal = when {
                    !guestMd5.equals(md5, ignoreCase = true) -> "The host is playing a different version of this game."
                    !accepting -> "The game has already started."
                    synchronized(guests) { guests.size } >= MAX_PLAYERS - 1 -> "The game is full."
                    else -> null
                }
                if (refusal != null) {
                    output.writeBoolean(false)
                    output.writeUTF(refusal)
                    output.flush()
                    throw NetplayException("refused $name: $refusal")
                }
                output.writeBoolean(true)
                output.writeUTF(deviceName())
                output.writeInt(config.options.size)
                config.options.forEach { (k, v) -> output.writeUTF(k); output.writeUTF(v) }
                output.writeInt(config.cheats.size)
                config.cheats.forEach(output::writeUTF)
                output.writeInt(config.saveMemory.size)
                output.write(config.saveMemory)
                output.flush()
                guest = Guest(socket, input, output, name, udpPort)
                synchronized(guests) { guests += guest }
                rosterChanged()

                // The guest boots now; that can take a while on a slow device.
                socket.soTimeout = 120_000
                if (!input.readBoolean()) throw NetplayException("$name failed to start: ${input.readUTF()}")
                guest.ready = true
                rosterChanged()

                // Nothing else comes from a guest over TCP: a read returning means it left.
                socket.soTimeout = 0
                input.read()
            } catch (e: IOException) {
                Log.w(TAG, "guest: ${e.message}")
            }
            runCatching { socket.close() }
            if (guest != null) {
                val inGame = guest.onLeft
                if (inGame != null) {
                    inGame()
                } else {
                    synchronized(guests) { guests -= guest }
                    rosterChanged()
                }
            }
        }

        /** Guests join and boot on their own threads: one change at a time, so nobody is told an older roster last. */
        private fun rosterChanged() = synchronized(rosterLock) {
            onChange()
            val names = roster().map { it.name }
            synchronized(guests) { guests.toList() }.forEach { guest ->
                guest.send { writeByte(MESSAGE_LOBBY); writeNames(names) }
            }
        }

        /**
         * Blocks until the host starts the game (and every guest has booted),
         * then tells each guest its player number. Null if [keepWaiting] turns
         * false first.
         */
        fun await(keepWaiting: () -> Boolean): HostSession? {
            while (true) {
                if (!keepWaiting()) {
                    close()
                    return null
                }
                if (startRequested && canStart()) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { }
            }
            accepting = false
            announcer.close()
            runCatching { server.close() }
            val players = synchronized(guests) { guests.filter { it.ready }.take(MAX_PLAYERS - 1) }
            val names = listOf(deviceName()) + players.map { it.name }
            val delay = inputDelayFor(names.size)
            val session = HostSession(udp, delay, names)
            players.forEachIndexed { index, guest ->
                val remote = session.addGuest(
                    slot = index + 1,
                    name = guest.name,
                    address = InetSocketAddress(guest.socket.inetAddress, guest.udpPort),
                    notify = { message -> guest.send { writeByte(MESSAGE_LEFT); writeUTF(message) } },
                    disconnect = { runCatching { guest.socket.close() } },
                )
                guest.onLeft = { session.depart(remote, "${guest.name} left the game.") }
                guest.send {
                    writeByte(MESSAGE_START)
                    writeInt(index + 1)
                    writeInt(delay)
                    writeNames(names)
                }
            }
            // Anyone still booting missed the start.
            synchronized(guests) { guests.filter { it !in players } }.forEach { runCatching { it.socket.close() } }
            return session
        }

        private fun close() {
            accepting = false
            announcer.close()
            runCatching { server.close() }
            synchronized(guests) { guests.toList() }.forEach { runCatching { it.socket.close() } }
            udp.close()
        }
    }

    // ── Host: running game ───────────────────────────────────────────────────

    /** The hub: collects every guest's input and sends complete frames back. */
    class HostSession(
        private val udp: DatagramSocket,
        private val inputDelay: Int,
        private val names: List<String>,
    ) : Session {
        class Remote(
            val slot: Int,
            val name: String,
            val address: InetSocketAddress,
            val notify: (String) -> Unit,
            val disconnect: () -> Unit,
        ) {
            val inputs = IntArray(RING)
            var latest = -1
            /** Once gone: the last frame that used this player's real input. */
            @Volatile var departedAfter = Int.MAX_VALUE
            var lastHeardMs = System.currentTimeMillis()
            val departed get() = departedAfter != Int.MAX_VALUE
        }

        override val localPlayer = 0
        override val playerCount = names.size
        private val remotes = mutableListOf<Remote>()
        private val local = IntArray(RING)
        private var localLatest = inputDelay - 1
        /** Every frame up to here has all its inputs. */
        private var complete = inputDelay - 1
        private val sendBuffer = ByteBuffer.allocate(4 + 1 + 4 + 1 + 1 + WINDOW * MAX_PLAYERS * 4 + 8)
        private val receiveBuffer = ByteArray(1024)
        private val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        private var lastSendMs = 0L
        private val checksums = ChecksumBook()
        private val stats = Stats()
        private val events = mutableListOf<String>()

        @Volatile override var closed = false
            private set
        @Volatile override var closeReason: String? = null
            private set
        override val desyncedAtFrame get() = checksums.desyncedAtFrame

        fun addGuest(
            slot: Int,
            name: String,
            address: InetSocketAddress,
            notify: (String) -> Unit,
            disconnect: () -> Unit,
        ): Remote {
            val remote = Remote(slot, name, address, notify, disconnect)
            // The first frames have no input yet; everyone starts them neutral.
            for (f in 0 until inputDelay) remote.inputs[f] = NEUTRAL
            remote.latest = inputDelay - 1
            synchronized(remotes) { remotes += remote }
            return remote
        }

        init {
            for (f in 0 until inputDelay) local[f] = NEUTRAL
        }

        private val departing = mutableListOf<Pair<Remote, String>>()

        /** From any thread: [remote] is gone. Takes effect on the emulation thread ([applyDepartures]). */
        fun depart(remote: Remote, message: String) {
            synchronized(departing) { departing += remote to message }
        }

        /**
         * Only here, on the emulation thread that sends frames, does a departed
         * player's input turn neutral: from the frame after the last complete one,
         * so every frame a guest has been sent keeps the input it was sent with.
         */
        private fun applyDepartures() {
            val pending = synchronized(departing) {
                if (departing.isEmpty()) return
                departing.toList().also { departing.clear() }
            }
            for ((remote, message) in pending) {
                if (remote.departed) continue
                synchronized(remotes) {
                    remote.departedAfter = complete
                    events += message
                }
                Log.i(TAG, "${remote.name} departed after frame $complete")
                remote.disconnect()
                synchronized(remotes) { remotes.filter { !it.departed } }.forEach { it.notify(message) }
            }
            if (synchronized(remotes) { remotes.all { it.departed } }) {
                close(pending.last().second)
            }
        }

        private fun inputOf(remote: Remote, frame: Int): Int =
            if (frame > remote.departedAfter) NEUTRAL else remote.inputs[frame % RING]

        private fun advanceComplete() {
            var limit = localLatest
            synchronized(remotes) {
                for (remote in remotes) {
                    if (!remote.departed) limit = minOf(limit, remote.latest)
                }
            }
            complete = maxOf(complete, limit)
        }

        override fun exchange(frame: Int, localInput: Int, onStall: (String?) -> Unit): IntArray? {
            val target = frame + inputDelay
            if (target > localLatest) {
                local[target % RING] = localInput
                localLatest = target
            }
            applyDepartures()
            advanceComplete()
            send()
            val waitStart = System.currentTimeMillis()
            var stalled = false
            while (complete < frame) {
                if (closed) return null
                val blockedOn = slowest()
                val t0 = System.currentTimeMillis()
                receive(4)
                blockedOn?.let { stats.waitedOn.merge(it.name, System.currentTimeMillis() - t0, Long::plus) }
                applyDepartures()
                advanceComplete()
                val now = System.currentTimeMillis()
                if (now - lastSendMs >= 16) send()
                if (!stalled && now - waitStart > STALL_NOTICE_MS) {
                    stalled = true
                    onStall(slowest()?.name ?: "")
                }
                checkTimeouts(now)
            }
            stats.waitedMs += System.currentTimeMillis() - waitStart
            while (receive(0)) { /* drain */ }
            advanceComplete()
            if (stalled) onStall(null)
            // Whatever completed while waiting goes out now rather than next frame.
            send()
            val out = IntArray(MAX_PLAYERS) { NEUTRAL }
            out[0] = local[frame % RING]
            synchronized(remotes) { remotes.forEach { out[it.slot] = inputOf(it, frame) } }
            return out
        }

        private fun slowest(): Remote? = synchronized(remotes) {
            remotes.filter { !it.departed }.minByOrNull { it.latest }
        }

        private fun checkTimeouts(now: Long) {
            synchronized(remotes) { remotes.filter { !it.departed && now - it.lastHeardMs > TIMEOUT_MS } }
                .forEach { depart(it, "Lost the connection to ${it.name}.") }
        }

        override fun idle() {
            if (closed) return
            val now = System.currentTimeMillis()
            if (now - lastSendMs >= 100) send()
            while (receive(0)) { /* drain */ }
            checkTimeouts(now)
            applyDepartures()
            advanceComplete()
        }

        override fun recordChecksum(frame: Int, checksum: Int) {
            checksums.record(frame, checksum)
            stats.log(frame, checksum)
        }

        override fun takeEvents(): List<String> = synchronized(remotes) {
            events.toList().also { events.clear() }
        }

        /** The latest complete frames, every player's input in each, to every guest still playing. */
        private fun send() {
            val first = maxOf(0, complete - WINDOW + 1)
            val count = complete - first + 1
            val bySlot = arrayOfNulls<Remote>(MAX_PLAYERS)
            val active = synchronized(remotes) {
                remotes.forEach { bySlot[it.slot] = it }
                remotes.filter { !it.departed }
            }
            sendBuffer.clear()
            sendBuffer.putInt(MAGIC)
            sendBuffer.put(PACKET_FRAMES)
            sendBuffer.putInt(complete)
            sendBuffer.put(count.toByte())
            sendBuffer.put(playerCount.toByte())
            for (f in first..complete) {
                sendBuffer.putInt(local[f % RING])
                for (slot in 1 until playerCount) {
                    val remote = bySlot[slot]
                    sendBuffer.putInt(if (remote == null) NEUTRAL else inputOf(remote, f))
                }
            }
            sendBuffer.putInt(checksums.lastFrame)
            sendBuffer.putInt(checksums.lastValue)
            for (remote in active) {
                runCatching { udp.send(DatagramPacket(sendBuffer.array(), sendBuffer.position(), remote.address)) }
            }
            lastSendMs = System.currentTimeMillis()
        }

        private fun receive(timeoutMs: Int): Boolean {
            try {
                udp.soTimeout = if (timeoutMs <= 0) 1 else timeoutMs
                udp.receive(receivePacket)
            } catch (_: SocketTimeoutException) {
                return false
            } catch (_: IOException) {
                return false
            }
            val length = receivePacket.length
            val b = ByteBuffer.wrap(receiveBuffer, 0, length)
            if (length < 6 || b.getInt() != MAGIC) return true
            val type = b.get()
            val slot = b.get().toInt()
            val remote = synchronized(remotes) { remotes.firstOrNull { it.slot == slot } } ?: return true
            if (remote.departed) return true
            if (type == PACKET_BYE) {
                depart(remote, "${remote.name} left the game.")
                return true
            }
            if (type != PACKET_INPUT || length < 11) return true
            val latest = b.getInt()
            val count = b.get().toInt() and 0xff
            if (length < 11 + count * 4 + 8) return true
            remote.lastHeardMs = System.currentTimeMillis()
            val first = latest - count + 1
            for (i in 0 until count) {
                val f = first + i
                val value = b.getInt()
                // Inputs only ever extend the known run, never jump past a gap.
                if (f == remote.latest + 1) {
                    remote.inputs[f % RING] = value
                    remote.latest = f
                }
            }
            checksums.compare(b.getInt(), b.getInt(), remote.name)
            return true
        }

        override fun close(reason: String?) {
            if (closed) return
            closeReason = reason
            closed = true
            val remaining = synchronized(remotes) { remotes.filter { !it.departed } }
            sendBye(udp, remaining.map { it.address }, 0)
            synchronized(remotes) { remotes.toList() }.forEach { it.disconnect() }
            udp.close()
        }
    }

    // ── Guest ────────────────────────────────────────────────────────────────

    /** Joining, before booting: connected to the host's lobby, with its session config. */
    class Joining(
        private val socket: Socket,
        private val udp: DatagramSocket,
        val hostName: String,
        val config: SessionConfig,
    ) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())

        /**
         * After booting with [config]: tells the host, then waits in its lobby
         * ([onLobby] gets who is in it) until the host starts the game.
         */
        fun ready(onLobby: (List<String>) -> Unit, keepWaiting: () -> Boolean): GuestSession {
            output.writeBoolean(true)
            output.flush()
            while (true) {
                if (!keepWaiting()) throw NetplayException("cancelled")
                socket.soTimeout = 500
                val type = try {
                    input.readByte().toInt()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // A message's first byte is here; the rest is right behind it.
                socket.soTimeout = 10_000
                when (type) {
                    MESSAGE_LOBBY -> onLobby(input.readNames())
                    MESSAGE_START -> {
                        val slot = input.readInt()
                        val delay = input.readInt()
                        val names = input.readNames()
                        socket.soTimeout = 0
                        return GuestSession(socket, input, udp, InetSocketAddress(socket.inetAddress, SESSION_PORT), slot, delay, names)
                    }
                    MESSAGE_LEFT -> input.readUTF()
                    else -> throw NetplayException("unexpected message $type")
                }
            }
        }

        fun fail(reason: String) {
            runCatching {
                output.writeBoolean(false)
                output.writeUTF(reason)
                output.flush()
            }
            close()
        }

        fun close() {
            runCatching { socket.close() }
            udp.close()
        }

        companion object {
            fun connect(host: String, port: Int, md5: String): Joining {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), 5000)
                    socket.soTimeout = 30_000
                    val udp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)) }
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val output = DataOutputStream(socket.getOutputStream().buffered())
                    output.writeInt(MAGIC)
                    output.writeInt(PROTOCOL_VERSION)
                    output.writeUTF(deviceName())
                    output.writeUTF(md5)
                    output.writeInt(udp.localPort)
                    output.flush()
                    if (!input.readBoolean()) {
                        udp.close()
                        throw NetplayException(input.readUTF())
                    }
                    val hostName = input.readUTF()
                    val options = List(input.readInt()) { input.readUTF() to input.readUTF() }
                    val cheats = List(input.readInt()) { input.readUTF() }
                    val saveMemory = ByteArray(input.readInt()).also(input::readFully)
                    return Joining(socket, udp, hostName, SessionConfig(options, cheats, saveMemory))
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
            }
        }
    }

    /** A guest's game: sends its own input to the host, runs the complete frames the host sends back. */
    class GuestSession(
        private val socket: Socket,
        private val control: DataInputStream,
        private val udp: DatagramSocket,
        private val host: InetSocketAddress,
        override val localPlayer: Int,
        private val inputDelay: Int,
        private val names: List<String>,
    ) : Session {
        override val playerCount = names.size
        private val local = IntArray(RING)
        private var localLatest = inputDelay - 1
        private val frames = IntArray(RING * MAX_PLAYERS)
        private var complete = inputDelay - 1
        private val sendBuffer = ByteBuffer.allocate(4 + 1 + 1 + 4 + 1 + WINDOW * 4 + 8)
        private val receiveBuffer = ByteArray(1024)
        private val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        private var lastSendMs = 0L
        private var lastHeardMs = System.currentTimeMillis()
        private val checksums = ChecksumBook()
        private val stats = Stats()
        private val events = mutableListOf<String>()

        @Volatile override var closed = false
            private set
        @Volatile override var closeReason: String? = null
            private set
        override val desyncedAtFrame get() = checksums.desyncedAtFrame

        init {
            for (f in 0 until inputDelay) {
                local[f] = NEUTRAL
                for (p in 0 until MAX_PLAYERS) frames[f * MAX_PLAYERS + p] = NEUTRAL
            }
            Thread({ watchControl() }, "Nin64-NetControl").apply { isDaemon = true; start() }
        }

        private fun watchControl() {
            try {
                while (true) {
                    when (control.readByte().toInt()) {
                        MESSAGE_LEFT -> synchronized(events) { events += control.readUTF() }
                        MESSAGE_LOBBY -> control.readNames()
                        else -> break
                    }
                }
            } catch (_: IOException) {
            }
            close("${names.first()} ended the game.")
        }

        override fun exchange(frame: Int, localInput: Int, onStall: (String?) -> Unit): IntArray? {
            val target = frame + inputDelay
            if (target > localLatest) {
                local[target % RING] = localInput
                localLatest = target
            }
            send()
            val waitStart = System.currentTimeMillis()
            var stalled = false
            while (complete < frame) {
                if (closed) return null
                receive(4)
                val now = System.currentTimeMillis()
                if (now - lastSendMs >= 16) send()
                if (!stalled && now - waitStart > STALL_NOTICE_MS) {
                    stalled = true
                    // Whoever is late, the host is who we hear it from; with two players it is the host.
                    onStall(if (playerCount == 2) names.first() else "")
                }
                if (now - lastHeardMs > TIMEOUT_MS) {
                    close("Lost the connection to ${names.first()}.")
                    return null
                }
            }
            stats.waitedMs += System.currentTimeMillis() - waitStart
            while (receive(0)) { /* drain */ }
            if (stalled) onStall(null)
            val base = (frame % RING) * MAX_PLAYERS
            return IntArray(MAX_PLAYERS) { frames[base + it] }
        }

        override fun idle() {
            if (closed) return
            val now = System.currentTimeMillis()
            if (now - lastSendMs >= 100) send()
            while (receive(0)) { /* drain */ }
            if (now - lastHeardMs > TIMEOUT_MS) close("Lost the connection to ${names.first()}.")
        }

        override fun recordChecksum(frame: Int, checksum: Int) {
            checksums.record(frame, checksum)
            stats.log(frame, checksum)
        }

        override fun takeEvents(): List<String> = synchronized(events) {
            events.toList().also { events.clear() }
        }

        private fun send() {
            val first = maxOf(0, localLatest - WINDOW + 1)
            val count = localLatest - first + 1
            sendBuffer.clear()
            sendBuffer.putInt(MAGIC)
            sendBuffer.put(PACKET_INPUT)
            sendBuffer.put(localPlayer.toByte())
            sendBuffer.putInt(localLatest)
            sendBuffer.put(count.toByte())
            for (f in first..localLatest) sendBuffer.putInt(local[f % RING])
            sendBuffer.putInt(checksums.lastFrame)
            sendBuffer.putInt(checksums.lastValue)
            runCatching { udp.send(DatagramPacket(sendBuffer.array(), sendBuffer.position(), host)) }
            lastSendMs = System.currentTimeMillis()
        }

        private fun receive(timeoutMs: Int): Boolean {
            try {
                udp.soTimeout = if (timeoutMs <= 0) 1 else timeoutMs
                udp.receive(receivePacket)
            } catch (_: SocketTimeoutException) {
                return false
            } catch (_: IOException) {
                return false
            }
            val length = receivePacket.length
            val b = ByteBuffer.wrap(receiveBuffer, 0, length)
            if (length < 5 || b.getInt() != MAGIC) return true
            when (b.get()) {
                PACKET_FRAMES -> if (length < 11) return true
                PACKET_BYE -> {
                    close("${names.first()} ended the game.")
                    return true
                }
                else -> return true
            }
            val latest = b.getInt()
            val count = b.get().toInt() and 0xff
            val players = b.get().toInt() and 0xff
            if (players !in 1..MAX_PLAYERS || length < 11 + count * players * 4 + 8) return true
            lastHeardMs = System.currentTimeMillis()
            val first = latest - count + 1
            for (i in 0 until count) {
                val f = first + i
                // Complete frames only ever extend the known run, never jump past a gap.
                val take = f == complete + 1
                for (p in 0 until players) {
                    val value = b.getInt()
                    if (take) frames[(f % RING) * MAX_PLAYERS + p] = value
                }
                if (take) {
                    for (p in players until MAX_PLAYERS) frames[(f % RING) * MAX_PLAYERS + p] = NEUTRAL
                    complete = f
                }
            }
            checksums.compare(b.getInt(), b.getInt(), names.first())
            return true
        }

        override fun close(reason: String?) {
            if (closed) return
            closeReason = reason
            closed = true
            sendBye(udp, listOf(host), localPlayer)
            runCatching { socket.close() }
            udp.close()
        }
    }
}
