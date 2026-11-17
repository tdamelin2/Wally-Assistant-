package dev.tim.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import dev.tim.assistant.study.STUDY_FILE_MAX_BYTES
import dev.tim.assistant.ui.SystemBackStack
import dev.tim.assistant.ui.study.FilePicker
import dev.tim.assistant.ui.study.PickedFile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import dev.tim.assistant.data.DriverFactory
import dev.tim.assistant.health.HEALTH_READ_PERMISSIONS
import dev.tim.assistant.health.HealthConnectSource
import dev.tim.assistant.health.HealthUi
import dev.tim.assistant.ui.App
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.usage.PhoneUsage
import dev.tim.assistant.usage.UsageAccess
import dev.tim.assistant.usage.usageAccessGranted

/** The chosen document: its display name and at most the study size limit plus one byte. */
private fun readPicked(context: Context, uri: Uri): PickedFile? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    val input = resolver.openInputStream(uri) ?: return null
    return input.use {
        val limit = STUDY_FILE_MAX_BYTES + 1
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = it.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        PickedFile(name, out.toByteArray())
    }
}

private const val NOTIFICATION_PERMISSION_ASKED_KEY = "notify.permissionAsked"

class MainActivity : ComponentActivity() {
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    /** A tap on a reminder (an Intent carrying the row's target) is handed to the app, which opens that place once. */
    private fun postLaunchTarget(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: return
        wallyServices().launch.post(target, intent.getStringExtra(EXTRA_BODY))
        intent.removeExtra(EXTRA_TARGET)
    }

    private fun askNotificationPermissionOnce(services: AppServices, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (services.repo.setting(NOTIFICATION_PERMISSION_ASKED_KEY) != null) return
        services.repo.putSetting(NOTIFICATION_PERMISSION_ASKED_KEY, "1")
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        postLaunchTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The theme is light only, so both bars keep dark icons whatever the system dark-mode setting is.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        super.onCreate(savedInstanceState)
        val services = wallyServices()

        // The launcher belongs to this Activity (it must be registered before the Activity starts).
        val launcher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val all = granted.containsAll(HEALTH_READ_PERMISSIONS)
            onPermissionResult?.invoke(all)
            onPermissionResult = null
            if (all) services.syncInBackground()
        }
        // Reminders: the notification permission is asked once, on its own (never together with Health Connect).
        val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (savedInstanceState == null) {
            postLaunchTarget(intent)
            askNotificationPermissionOnce(services, notificationLauncher)
        }
        val appContext = applicationContext
        val healthUi = object : HealthUi {
            override fun available() =
                HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

            override suspend fun hasPermissions(): Boolean =
                HealthConnectClient.getOrCreate(appContext).permissionController
                    .getGrantedPermissions().containsAll(HEALTH_READ_PERMISSIONS)

            override fun requestPermissions(onResult: (Boolean) -> Unit) {
                onPermissionResult = onResult
                launcher.launch(HEALTH_READ_PERMISSIONS)
            }
        }

        // The document chooser also belongs to this Activity; only the FilePicker capability is handed to the UI.
        var pendingPick: CompletableDeferred<Uri?>? = null
        val documentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingPick?.complete(uri)
            pendingPick = null
        }
        val filePicker = FilePicker {
            val waiting = CompletableDeferred<Uri?>()
            pendingPick?.cancel()
            pendingPick = waiting
            documentLauncher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            val uri = waiting.await() ?: return@FilePicker null
            withContext(Dispatchers.IO) { readPicked(appContext, uri) }
        }

        // The Activity owns the settings jump; the UI only gets this small capability.
        val usageAccess = object : UsageAccess {
            override fun granted(): Boolean = usageAccessGranted(appContext)

            override fun openSettings() {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        setContent {
            // The 10-minute timer runs only while the screen is shown (STARTED or above).
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            // Back closes the innermost open screen (history, writer, detail) before leaving the app.
            BackHandler(enabled = SystemBackStack.active) { SystemBackStack.back() }
            App(services, healthUi, filePicker, usageAccess, foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED))
        }
    }
}
