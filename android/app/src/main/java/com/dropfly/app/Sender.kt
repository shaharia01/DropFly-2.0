package com.dropfly.app

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.UUID

data class Target(val host: String, val port: Int, val name: String)

object Sender {
    private const val DISCOVERY_PORT = 53318
    private const val BUF = 256 * 1024

    fun prefs(ctx: Context) = ctx.getSharedPreferences("dropfly", Context.MODE_PRIVATE)

    /** Try the last known laptop first, otherwise shout on the Wi-Fi and wait for an answer. */
    fun findTarget(ctx: Context, code: String): Target? {
        val p = prefs(ctx)
        val cachedHost = p.getString("host", null)
        val cachedPort = p.getInt("port", 53317)
        if (cachedHost != null && ping(cachedHost, cachedPort, code)) {
            return Target(cachedHost, cachedPort, p.getString("name", "laptop") ?: "laptop")
        }
        val found = discover(ctx) ?: return null
        p.edit().putString("host", found.host).putInt("port", found.port).putString("name", found.name).apply()
        return found
    }

    private fun ping(host: String, port: Int, code: String): Boolean = try {
        val c = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
        c.connectTimeout = 600
        c.readTimeout = 600
        c.setRequestProperty("X-Token", code)
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun discover(ctx: Context): Target? {
        val msg = "DROPFLY?".toByteArray()
        val addrs = mutableListOf<InetAddress>()
        try {
            addrs.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            val d = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
            val b = (d.ipAddress and d.netmask) or d.netmask.inv()
            if (d.netmask != 0) {
                addrs.add(
                    InetAddress.getByAddress(
                        byteArrayOf(b.toByte(), (b shr 8).toByte(), (b shr 16).toByte(), (b shr 24).toByte())
                    )
                )
            }
        } catch (e: Exception) {
        }

        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 450
            repeat(4) {
                for (a in addrs) {
                    try {
                        s.send(DatagramPacket(msg, msg.size, a, DISCOVERY_PORT))
                    } catch (e: Exception) {
                    }
                }
                try {
                    val buf = ByteArray(256)
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length)
                    if (text.startsWith("DROPFLY!")) {
                        val parts = text.removePrefix("DROPFLY!").split("|")
                        val port = parts[0].toIntOrNull() ?: 53317
                        val name = parts.getOrNull(1) ?: "laptop"
                        return Target(pkt.address.hostAddress ?: return null, port, name)
                    }
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    /** How many bytes of this transfer the laptop already holds (0 if none / unreachable). */
    private fun status(t: Target, code: String, id: String): Long = try {
        val c = URL("http://${t.host}:${t.port}/status?id=$id").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2000
        c.setRequestProperty("X-Token", code)
        val v = if (c.responseCode == 200) c.inputStream.bufferedReader().readText().trim().toLongOrNull() ?: 0L else 0L
        c.disconnect()
        v
    } catch (e: Exception) {
        0L
    }

    /**
     * Sends one file. Big files are fine: if the connection drops (Wi-Fi hiccup, phone sleeps for a moment)
     * it reconnects and continues from where the laptop stopped instead of starting over.
     * onProgress(sentBytes, totalBytes) is called while sending.
     */
    fun sendFile(ctx: Context, t0: Target, code: String, uri: Uri, onProgress: (Long, Long) -> Unit): Boolean {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }

        var tmp: File? = null
        try {
            val open: () -> InputStream?
            if (size >= 0) {
                open = { cr.openInputStream(uri) }
            } else {
                // size unknown: stage a copy so we can announce the length
                val f = File.createTempFile("up", null, ctx.cacheDir)
                tmp = f
                (cr.openInputStream(uri) ?: return false).use { i -> f.outputStream().use { o -> i.copyTo(o, BUF) } }
                size = f.length()
                open = { f.inputStream() }
            }

            val id = UUID.randomUUID().toString().replace("-", "")
            var t = t0
            var offset = 0L
            for (attempt in 0..6) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(1000L * attempt)
                    } catch (e: InterruptedException) {
                        return false
                    }
                    t = findTarget(ctx, code) ?: continue
                    offset = status(t, code, id)
                }
                try {
                    post(t, code, open, name, size, id, offset, onProgress)
                    return true
                } catch (e: IOException) {
                    // connection problem: loop around and resume
                }
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            tmp?.delete()
        }
    }

    private fun skipFully(s: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = s.skip(left)
            if (n > 0) {
                left -= n
            } else {
                if (s.read() < 0) throw IOException("file shorter than expected")
                left--
            }
        }
    }

    private fun post(
        t: Target, code: String, open: () -> InputStream?, name: String,
        size: Long, id: String, offset: Long, onProgress: (Long, Long) -> Unit
    ) {
        val input = open() ?: throw IOException("cannot open file")
        input.use { ins ->
            skipFully(ins, offset)
            val conn = URL("http://${t.host}:${t.port}/upload?name=${Uri.encode(name)}&id=$id")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.useCaches = false
                conn.connectTimeout = 4000
                conn.readTimeout = 60000
                conn.setFixedLengthStreamingMode(size - offset)
                conn.setRequestProperty("X-Token", code)
                conn.setRequestProperty("X-Total", size.toString())
                conn.setRequestProperty("X-Offset", offset.toString())

                val buf = ByteArray(BUF)
                var sent = offset
                conn.outputStream.use { o ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        sent += n
                        onProgress(sent, size)
                    }
                }
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun sendText(t: Target, code: String, text: String): Boolean = try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val conn = URL("http://${t.host}:${t.port}/text").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = 10000
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.setRequestProperty("X-Token", code)
        conn.outputStream.use { it.write(bytes) }
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }
}
