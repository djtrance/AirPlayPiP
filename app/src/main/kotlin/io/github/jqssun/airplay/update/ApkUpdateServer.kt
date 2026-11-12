package io.github.jqssun.airplay.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.github.jqssun.airplay.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApkUpdateState(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val urls: List<String> = emptyList(),
    val status: String = "",
    val progress: Int? = null,
    val installReady: Boolean = false,
    val version: Long? = null,
    val authMode: UpdateAuthMode = UpdateAuthMode.FOUR_DIGIT
)

/** Opt-in LAN-only, one-upload session. APK execution is always delegated to Android's installer UI. */
class ApkUpdateServer(context: Context) {
    private val app = context.applicationContext
    private val stateMutable = MutableStateFlow(ApkUpdateState())
    val state = stateMutable.asStateFlow()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var sessionToken = ""
    @Volatile private var consumed = false
    @Volatile private var authMode = UpdateAuthMode.FOUR_DIGIT
    private val failedCodes = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(MAX_CLIENTS)
    @Volatile private var pendingApk: File? = null
    @Volatile private var pendingVersion: Long? = null

    @Synchronized
    fun start(mode: UpdateAuthMode = UpdateAuthMode.FOUR_DIGIT): ApkUpdateState {
        cleanupStaleUploads()
        val pending = pendingApk
        if (pending != null && pending.exists() && (pendingVersion ?: Long.MAX_VALUE) <= installedVersion()) {
            pending.delete()
            pendingApk = null
            pendingVersion = null
        }
        if (pendingApk?.exists() == true) return stateMutable.value.copy(
            running = false, token = "", status = app.getString(R.string.update_server_pending_install), installReady = true)
        if (listener?.isClosed == false) return stateMutable.value
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), MAX_CLIENTS)
            socket.soTimeout = 1_000
            listener = socket
            closed.set(false)
            consumed = false
            authMode = mode
            failedCodes.set(0)
            sessionToken = if (mode == UpdateAuthMode.FOUR_DIGIT) UpdateAuthPolicy.newFourDigitCode() else ""
            val port = socket.localPort
            val ips = UpdateNetwork.addresses(NetworkInterface.getNetworkInterfaces().toList())
            val urls = ips.map { "http://$it:$port/" }
            stateMutable.value = ApkUpdateState(running = true, port = port, token = sessionToken, urls = urls,
                status = if (urls.isEmpty()) app.getString(R.string.update_server_no_ip) else app.getString(R.string.update_server_waiting),
                authMode = mode)
            acceptThread = Thread({ acceptLoop(socket) }, "AirPlayApkUpdate").apply {
                isDaemon = true
                start()
            }
            stateMutable.value
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) { }
            stateMutable.value = ApkUpdateState(status = app.getString(R.string.update_server_start_error, e.message ?: e.javaClass.simpleName))
            stateMutable.value
        }
    }

    @Synchronized
    fun stop(message: String = "") {
        closed.set(true)
        sessionToken = ""
        try { listener?.close() } catch (_: Exception) { }
        listener = null
        clients.forEach { try { it.close() } catch (_: Exception) { } }
        clients.clear()
        acceptThread = null
        val pending = pendingApk?.takeIf { it.exists() }
        stateMutable.value = ApkUpdateState(status = if (pending != null) app.getString(R.string.update_server_pending_install) else message,
            installReady = pending != null, version = stateMutable.value.version, authMode = authMode)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!closed.get() && !server.isClosed) {
            val client = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                catch (e: SocketException) { if (!closed.get()) Log.w(TAG, "Update listener stopped", e); break }
                catch (e: IOException) { if (!closed.get()) Log.w(TAG, "Update accept failed", e); break }
            if (!permits.tryAcquire()) {
                runCatching { client.use { respond(BufferedOutputStream(it.getOutputStream()), 503, "Too many requests") } }
                continue
            }
            clients += client
            Thread({
                try { client.use(::handle) }
                catch (e: Exception) { if (!closed.get()) Log.i(TAG, "Rejected update request: ${e.message}") }
                finally { clients.remove(client); permits.release() }
            }, "AirPlayApkClient").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        if (!UpdateNetwork.allowRemote(socket.inetAddress)) {
            respond(output, 403, "Local network access only")
            return
        }
        val request = HttpUploadRequestParser.parse(input)
        when {
            request.method == "GET" && request.path == "/" -> respond(output, 200, PAGE, "text/html; charset=utf-8")
            request.method == "GET" && request.path == "/status" -> respond(output, 200,
                "{\"ready\":${!consumed && !closed.get()},\"consumed\":$consumed}", "application/json; charset=utf-8")
            request.method != "PUT" || request.path != "/upload" -> respond(output, 404, "Not found")
            consumed -> respond(output, 410, "This upload session has already been used. Start a new session on the TV.")
            authMode == UpdateAuthMode.FOUR_DIGIT && failedCodes.get() >= UpdateAuthPolicy.MAX_FAILED_CODES ->
                respond(output, 423, "Too many invalid codes. Start a new session on the TV.")
            UpdateAuthPolicy.authenticate(authMode, sessionToken, request.headers["x-update-token"]) == UpdateAuthResult.INVALID_CODE -> {
                val failures = failedCodes.incrementAndGet()
                respond(output, 401, if (failures >= UpdateAuthPolicy.MAX_FAILED_CODES)
                    "Too many invalid codes. Start a new session on the TV." else "Invalid code; $failures/${UpdateAuthPolicy.MAX_FAILED_CODES} attempts used")
                if (failures >= UpdateAuthPolicy.MAX_FAILED_CODES) stop(app.getString(R.string.update_server_locked))
            }
            else -> receiveAndInstall(request.headers, input, output)
        }
    }

    private fun receiveAndInstall(headers: Map<String, String>, input: BufferedInputStream, output: BufferedOutputStream) {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length < 8 || length > MAX_APK_BYTES) {
            respond(output, 413, "Content-Length must be 8..$MAX_APK_BYTES bytes")
            return
        }
        if (!uploadClaimed.compareAndSet(false, true)) {
            respond(output, 409, "An APK upload is already in progress")
            return
        }
        stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_server_receiving), progress = 0)
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        var apk: File? = null
        try {
            val incoming = File.createTempFile("incoming-", ".apk", dir)
            apk = incoming
            val digest = MessageDigest.getInstance("SHA-256")
            incoming.outputStream().buffered(64 * 1024).use { file ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                var copied = 0L
                val startedAt = System.nanoTime()
                while (remaining > 0) {
                    if (System.nanoTime() - startedAt > UPLOAD_TIMEOUT_NANOS) throw IOException("APK upload exceeded the 10 minute limit")
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("Incomplete APK upload")
                    file.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    remaining -= count
                    copied += count
                    stateMutable.value = stateMutable.value.copy(progress = (copied * 100 / length).toInt())
                }
            }
            if (!isApkZip(incoming)) throw UpdateRejected("The uploaded file is not an APK archive")
            val archive = inspect(incoming) ?: throw UpdateRejected("Android could not read this APK")
            validateArchive(archive)
            val version = archive.longVersionCodeCompat()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            consumed = true
            sessionToken = ""
            pendingApk?.takeIf { it != incoming }?.delete()
            pendingApk = incoming
            pendingVersion = version
            stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_server_verified, version.toString(), hash.take(16)), progress = 100, installReady = true, version = version, authMode = authMode)
            respond(output, 202, "APK verified. Check the TV for Android's installation confirmation.")
            // The capability is single-use; close the port immediately after the accepted response.
            stop(app.getString(R.string.update_server_pending_install))
            Log.i(TAG, "Verified LAN update version=$version sha256=$hash")
        } catch (e: UpdateRejected) {
            apk?.delete()
            stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_server_rejected, e.message ?: "Invalid APK"), progress = null)
            respond(output, 400, e.message ?: "Invalid APK")
        } catch (e: Exception) {
            apk?.delete()
            stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_server_failed, e.message ?: e.javaClass.simpleName), progress = null)
            runCatching { respond(output, 400, "Upload failed: ${e.message ?: "invalid request"}") }
        } finally {
            uploadClaimed.set(false)
        }
    }

    private val uploadClaimed = AtomicBoolean(false)

    @Suppress("DEPRECATION")
    private fun inspect(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        app.packageManager.getPackageArchiveInfo(file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_ACTIVITIES)
    } else app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)

    private fun validateArchive(archive: PackageInfo) {
        val installed = installedPackage()
        val candidate = ApkUpdateMetadata(archive.packageName, archive.longVersionCodeCompat(), signerDigests(archive))
        val current = ApkUpdateMetadata(app.packageName, installed.longVersionCodeCompat(), signerDigests(installed))
        ApkUpdatePolicy.rejection(current, candidate)?.let { throw UpdateRejected(it) }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)

    private fun installedVersion() = installedPackage().longVersionCodeCompat()

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }?.toSet().orEmpty()
    }

    /** The Activity launches the returned intent from an explicit TV button. */
    fun pendingInstallerIntent(): Intent? {
        val apk = pendingApk ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            stateMutable.value = stateMutable.value.copy(running = false, token = "", status = app.getString(R.string.update_server_install_permission))
            return null
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
            stateMutable.value = stateMutable.value.copy(running = false, token = "", status = app.getString(R.string.update_server_install_prompt, stateMutable.value.version?.toString() ?: "?"))
            return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open package installer", e)
            stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_server_install_error, e.message ?: e.javaClass.simpleName))
            return null
        }
    }

    fun completePendingInstaller(success: Boolean) {
        if (success) {
            pendingApk?.delete()
            pendingApk = null
            pendingVersion = null
            stateMutable.value = ApkUpdateState(status = app.getString(R.string.update_install_complete))
        } else {
            stateMutable.value = stateMutable.value.copy(status = app.getString(R.string.update_install_cancelled), installReady = pendingApk?.exists() == true)
        }
    }

    private fun cleanupStaleUploads() {
        val cutoff = System.currentTimeMillis() - STALE_UPLOAD_MS
        File(app.cacheDir, "updates").listFiles()
            ?.filter { it.name.startsWith("incoming-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun isApkZip(file: File): Boolean = file.inputStream().use { input ->
        val prefix = ByteArray(4)
        input.read(prefix) == 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            prefix[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte()) && prefix[3] in setOf(0x04.toByte(), 0x06.toByte(), 0x08.toByte())
    }

    private fun respond(output: BufferedOutputStream, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 410 -> "Gone"; 413 -> "Payload Too Large"; 503 -> "Busy"; else -> "Error" }
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private data class UpdateRejected(override val message: String) : Exception(message)

    companion object {
        private const val TAG = "ApkUpdateServer"
        private const val MAX_CLIENTS = 2
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val UPLOAD_TIMEOUT_NANOS = 10L * 60 * 1_000_000_000
        private const val STALE_UPLOAD_MS = 24 * 60 * 60 * 1_000L
        private const val APK_MIME = "application/vnd.android.package-archive"

        private const val PAGE = """
<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AirPlay PiP · Actualizar</title><style>
body{font:18px system-ui;background:#080c18;color:#f5f7ff;max-width:680px;margin:8vh auto;padding:24px}main{background:#101727;padding:28px;border-radius:20px}input,button{font:inherit;margin:12px 0;padding:12px;max-width:100%}button{background:#8cbfff;border:0;border-radius:12px;color:#002d61}small{color:#bac6dc}progress{width:100%}
</style><main><h1>AirPlay PiP · Actualizar</h1><p>Elige el APK nuevo y escribe el código que aparece en la TV.</p>
<label>Código de 4 dígitos (si elegiste sin código, dejar vacío)<br><input id="token" inputmode="numeric" autocomplete="one-time-code" maxlength="4" placeholder="0000"></label><br>
<label>Archivo APK<br><input id="file" type="file" accept=".apk,application/vnd.android.package-archive"></label><br>
<button id="send">Validar y enviar</button><progress id="progress" max="100" value="0"></progress><p id="status" role="status">El APK se valida antes de abrir el instalador de Android.</p>
<small>Solo red local. El código autoriza una carga. La instalación siempre requiere confirmación en la TV.</small></main>
<script>
document.querySelector('#send').onclick=async()=>{const f=document.querySelector('#file').files[0],t=document.querySelector('#token').value.trim(),s=document.querySelector('#status'),p=document.querySelector('#progress');if(!f){s.textContent='Selecciona un APK.';return}if(!f.name.toLowerCase().endsWith('.apk')){s.textContent='El archivo debe tener extensión .apk';return}if(t&&(t.length!==4||Array.from(t).some(c=>c<'0'||c>'9'))){s.textContent='El código debe tener exactamente cuatro dígitos.';return}const x=new XMLHttpRequest();x.open('PUT','/upload');if(t)x.setRequestHeader('X-Update-Token',t);x.setRequestHeader('Content-Type','application/vnd.android.package-archive');x.upload.onprogress=e=>{if(e.lengthComputable)p.value=Math.round(e.loaded*100/e.total)};x.onload=()=>{s.textContent=x.responseText;document.querySelector('#token').value='';if(x.status===202)p.value=100};x.onerror=()=>s.textContent='Error de red; comprueba que la TV y el navegador estén en la misma red.';s.textContent='Subiendo y verificando…';x.send(f)};
</script></html>
"""
    }
}
