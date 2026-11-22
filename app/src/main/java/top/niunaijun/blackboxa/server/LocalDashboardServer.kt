package top.niunaijun.blackboxa.server

import android.location.Location
import android.util.Log
import org.json.JSONObject
import top.niunaijun.blackboxa.location.SimulatedLocationProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Embedded Localhost HTTP Web Dashboard Server.
 * Runs on http://127.0.0.1:8080 and provides:
 * 1. Cyber-themed HTML5/JS Web Dashboard for testing and injecting GPS coordinates.
 * 2. REST API endpoints for telemetry and route simulation.
 * 3. Localhost fallback for license authentication (/verify_key.php).
 */
object LocalDashboardServer {

    private const val TAG = "LocalDashboardServer"
    const val PORT = 8080

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Local Dashboard Server listening on http://127.0.0.1:$PORT")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server error: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true
            name = "LocalDashboardServer"
            start()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val output = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val fullUrl = parts[1]

                // Parse headers
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                    }
                }

                // Read body if POST/PUT
                var body = ""
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(buf, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    body = String(buf, 0, readTotal)
                }

                val queryIndex = fullUrl.indexOf('?')
                val path = if (queryIndex != -1) fullUrl.substring(0, queryIndex) else fullUrl
                val queryString = if (queryIndex != -1) fullUrl.substring(queryIndex + 1) else ""
                val params = parseParams(queryString, body)

                when {
                    path == "/" || path == "/index.html" || path == "/dashboard" -> {
                        sendHtmlResponse(output, getDashboardHtml())
                    }
                    path == "/api/status" -> {
                        val loc = SimulatedLocationProvider.getLastKnownLocation()
                        val json = JSONObject().apply {
                            put("status", "ok")
                            put("latitude", loc?.latitude ?: 23.8103)
                            put("longitude", loc?.longitude ?: 90.4125)
                            put("altitude", loc?.altitude ?: 10.0)
                            put("accuracy", loc?.accuracy ?: 5.0f)
                            put("speed", (loc?.speed ?: 0.0f) * 3.6f) // km/h
                            put("isRouteRunning", SimulatedLocationProvider.isRouteRunning())
                            put("server", "localhost:$PORT")
                        }
                        sendJsonResponse(output, json.toString())
                    }
                    path == "/api/set-location" -> {
                        val lat = params["lat"]?.toDoubleOrNull() ?: 23.8103
                        val lng = params["lng"]?.toDoubleOrNull() ?: 90.4125
                        if (SimulatedLocationProvider.isRouteRunning()) {
                            SimulatedLocationProvider.stopRoute()
                        }
                        SimulatedLocationProvider.setLocation(lat, lng)
                        val resp = JSONObject().apply {
                            put("status", "success")
                            put("message", "Location updated to $lat, $lng")
                            put("latitude", lat)
                            put("longitude", lng)
                        }
                        sendJsonResponse(output, resp.toString())
                    }
                    path == "/api/start-route" -> {
                        val lat = params["lat"]?.toDoubleOrNull() ?: 23.8103
                        val lng = params["lng"]?.toDoubleOrNull() ?: 90.4125
                        val waypoints = listOf(
                            Pair(lat, lng),
                            Pair(lat + 0.0035, lng + 0.0025),
                            Pair(lat + 0.0050, lng - 0.0010),
                            Pair(lat + 0.0020, lng - 0.0035),
                            Pair(lat, lng)
                        )
                        SimulatedLocationProvider.startRoute(waypoints)
                        val resp = JSONObject().apply {
                            put("status", "success")
                            put("message", "30 km/h Route simulation started")
                            put("isRouteRunning", true)
                        }
                        sendJsonResponse(output, resp.toString())
                    }
                    path == "/api/stop-route" -> {
                        SimulatedLocationProvider.stopRoute()
                        val resp = JSONObject().apply {
                            put("status", "success")
                            put("message", "Route simulation stopped")
                            put("isRouteRunning", false)
                        }
                        sendJsonResponse(output, resp.toString())
                    }
                    path == "/verify_key.php" -> {
                        // Localhost license verification endpoint
                        val resp = JSONObject().apply {
                            put("status", "success")
                            put("token", "zero_local_master_token_vip")
                            put("message", "Localhost Authentication Successful")
                        }
                        sendJsonResponse(output, resp.toString())
                    }
                    else -> {
                        sendNotFound(output)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request: ${e.message}")
        }
    }

    private fun parseParams(query: String, body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        listOf(query, body).forEach { raw ->
            if (raw.isNotBlank()) {
                if (raw.trim().startsWith("{")) {
                    try {
                        val json = JSONObject(raw)
                        for (k in json.keys()) {
                            map[k] = json.optString(k)
                        }
                    } catch (ignored: Exception) {}
                } else {
                    raw.split("&").forEach { pair ->
                        val kv = pair.split("=")
                        if (kv.isNotEmpty()) {
                            val key = URLDecoder.decode(kv[0], "UTF-8")
                            val value = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                            map[key] = value
                        }
                    }
                }
            }
        }
        return map
    }

    private fun sendJsonResponse(output: OutputStream, jsonStr: String) {
        val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        val headers = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun sendHtmlResponse(output: OutputStream, htmlStr: String) {
        val bytes = htmlStr.toByteArray(StandardCharsets.UTF_8)
        val headers = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val bytes = "404 Not Found".toByteArray(StandardCharsets.UTF_8)
        val headers = ("HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun getDashboardHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ZERO LOCATION // Localhost Dashboard</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace; }
        body { background: #0B0F19; color: #E2E8F0; padding: 20px; display: flex; justify-content: center; }
        .container { width: 100%; max-width: 580px; }
        .header { text-align: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid #1E293B; }
        .header h1 { color: #00FF66; font-size: 24px; letter-spacing: 2px; text-transform: uppercase; }
        .header p { color: #94A3B8; font-size: 12px; margin-top: 4px; letter-spacing: 1px; }
        .badge { display: inline-block; background: rgba(0,255,102,0.1); color: #00FF66; border: 1px solid rgba(0,255,102,0.3); font-size: 11px; padding: 4px 10px; border-radius: 4px; margin-top: 8px; font-weight: bold; }
        
        .card { background: #131B2E; border: 1px solid #1E293B; border-radius: 12px; padding: 18px; margin-bottom: 18px; }
        .card-title { font-size: 12px; font-weight: bold; color: #38BDF8; letter-spacing: 1.5px; margin-bottom: 12px; text-transform: uppercase; }
        
        .telemetry-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
        .telem-box { background: #0A0E1A; padding: 12px; border-radius: 8px; border: 1px solid #1E293B; }
        .telem-label { font-size: 10px; color: #64748B; text-transform: uppercase; }
        .telem-value { font-size: 16px; font-weight: bold; color: #F8FAFC; margin-top: 4px; word-break: break-all; }
        
        .form-group { margin-bottom: 14px; }
        label { display: block; font-size: 11px; color: #94A3B8; margin-bottom: 6px; text-transform: uppercase; font-weight: bold; }
        input[type="text"], input[type="number"], select {
            width: 100%; background: #0A0E1A; border: 1px solid #334155; border-radius: 8px; color: #F8FAFC; padding: 10px 14px; font-size: 14px; outline: none; transition: 0.2s;
        }
        input:focus { border-color: #00FF66; box-shadow: 0 0 8px rgba(0,255,102,0.2); }
        
        .btn-group { display: flex; gap: 10px; margin-top: 14px; }
        button {
            flex: 1; padding: 12px; border-radius: 8px; font-size: 13px; font-weight: bold; cursor: pointer; border: none; transition: 0.2s; text-transform: uppercase; letter-spacing: 0.5px;
        }
        .btn-primary { background: #00FF66; color: #000; }
        .btn-primary:hover { background: #00DD55; }
        .btn-secondary { background: #0284C7; color: #FFF; }
        .btn-secondary:hover { background: #0369A1; }
        .btn-danger { background: #EF4444; color: #FFF; }
        .btn-danger:hover { background: #DC2626; }
        
        #toast {
            position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #1E293B; color: #00FF66; padding: 10px 20px; border-radius: 20px; font-size: 12px; border: 1px solid #00FF66; display: none; z-index: 999;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Zero Location</h1>
            <p>Localhost Telemetry & GPS Controller</p>
            <div class="badge">● LOCALHOST SERVER ACTIVE (PORT 8080)</div>
        </div>

        <div class="card">
            <div class="card-title">Live GPS Telemetry</div>
            <div class="telemetry-grid">
                <div class="telem-box">
                    <div class="telem-label">Latitude</div>
                    <div class="telem-value" id="valLat">--</div>
                </div>
                <div class="telem-box">
                    <div class="telem-label">Longitude</div>
                    <div class="telem-value" id="valLng">--</div>
                </div>
                <div class="telem-box">
                    <div class="telem-label">Speed</div>
                    <div class="telem-value" id="valSpeed">0 km/h</div>
                </div>
                <div class="telem-box">
                    <div class="telem-label">Mode / Status</div>
                    <div class="telem-value" id="valMode" style="color: #10B981;">Idle</div>
                </div>
            </div>
        </div>

        <div class="card">
            <div class="card-title">Inject Simulated Coordinates</div>
            
            <div class="form-group">
                <label>Quick Location Presets</label>
                <select id="selPreset" onchange="applyPreset()">
                    <option value="custom">-- Custom Coordinates --</option>
                    <option value="23.8103,90.4125" selected>Dhaka (Gulshan / Banani)</option>
                    <option value="23.7465,90.3752">Dhaka (Dhanmondi)</option>
                    <option value="22.3569,91.7832">Chittagong (Agrabad)</option>
                    <option value="24.8949,91.8687">Sylhet (Zindabazar)</option>
                    <option value="22.8456,89.5403">Khulna</option>
                </select>
            </div>

            <div class="form-group">
                <label>Target Latitude</label>
                <input type="text" id="inLat" value="23.8103">
            </div>

            <div class="form-group">
                <label>Target Longitude</label>
                <input type="text" id="inLng" value="90.4125">
            </div>

            <div class="btn-group">
                <button class="btn-primary" onclick="injectLocation()">Set Location</button>
                <button class="btn-secondary" id="btnRoute" onclick="toggleRoute()">Start Route (30 km/h)</button>
            </div>
        </div>

        <div class="card" style="text-align: center;">
            <p style="font-size: 11px; color: #64748B;">
                Hardware GPS Spoofing Active &bull; Accuracy: 5m &bull; Altitude: 10m<br>
                Rest API: <code>/api/set-location?lat=...&lng=...</code> &bull; <code>/api/status</code>
            </p>
        </div>
    </div>

    <div id="toast"></div>

    <script>
        let isRouteRunning = false;

        function showToast(msg) {
            const t = document.getElementById('toast');
            t.innerText = msg;
            t.style.display = 'block';
            setTimeout(() => { t.style.display = 'none'; }, 2500);
        }

        function applyPreset() {
            const val = document.getElementById('selPreset').value;
            if (val !== 'custom') {
                const parts = val.split(',');
                document.getElementById('inLat').value = parts[0];
                document.getElementById('inLng').value = parts[1];
            }
        }

        async function pollStatus() {
            try {
                const res = await fetch('/api/status');
                if (res.ok) {
                    const data = await res.json();
                    document.getElementById('valLat').innerText = Number(data.latitude).toFixed(6);
                    document.getElementById('valLng').innerText = Number(data.longitude).toFixed(6);
                    document.getElementById('valSpeed').innerText = Math.round(data.speed) + ' km/h';
                    
                    isRouteRunning = data.isRouteRunning;
                    const modeElem = document.getElementById('valMode');
                    const btnRoute = document.getElementById('btnRoute');

                    if (isRouteRunning) {
                        modeElem.innerText = 'Simulating (30 km/h)';
                        modeElem.style.color = '#38BDF8';
                        btnRoute.innerText = 'Stop Route';
                        btnRoute.className = 'btn-danger';
                    } else {
                        modeElem.innerText = 'Static GPS';
                        modeElem.style.color = '#10B981';
                        btnRoute.innerText = 'Start Route (30 km/h)';
                        btnRoute.className = 'btn-secondary';
                    }
                }
            } catch (e) {
                console.warn('Status poll error:', e);
            }
        }

        async function injectLocation() {
            const lat = document.getElementById('inLat').value;
            const lng = document.getElementById('inLng').value;
            try {
                const res = await fetch('/api/set-location?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng));
                const data = await res.json();
                showToast(data.message || 'Location updated');
                pollStatus();
            } catch (e) {
                showToast('Failed to update: ' + e);
            }
        }

        async function toggleRoute() {
            const lat = document.getElementById('inLat').value;
            const lng = document.getElementById('inLng').value;
            try {
                if (isRouteRunning) {
                    const res = await fetch('/api/stop-route');
                    const data = await res.json();
                    showToast(data.message || 'Route stopped');
                } else {
                    const res = await fetch('/api/start-route?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng));
                    const data = await res.json();
                    showToast(data.message || 'Route started');
                }
                pollStatus();
            } catch (e) {
                showToast('Action failed: ' + e);
            }
        }

        setInterval(pollStatus, 1500);
        pollStatus();
    </script>
</body>
</html>
        """.trimIndent()
    }
}
