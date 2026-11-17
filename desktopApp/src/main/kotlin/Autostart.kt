import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * "Start with Windows": a value `Wally` under the current user's Run key that starts the installed app with `--tray`.
 * Only works for the installed app (jpackage sets `jpackage.app-path`); a development run never registers java.exe.
 */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Wally"
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun appPath(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    fun available(): Boolean = windows && appPath() != null

    fun enabled(): Boolean = windows && try {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME)
    } catch (e: Throwable) {
        false
    }

    fun set(on: Boolean) {
        if (!windows) return
        try {
            if (on) {
                val path = appPath() ?: return
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$path\" --tray")
            } else if (enabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
            }
        } catch (e: Throwable) {
            // the switch re-reads the registry the next time Settings opens
        }
    }
}
