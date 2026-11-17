import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import dev.tim.assistant.desktop.Sample

/**
 * Reads the program in front, its window title and the idle time with plain Windows calls (JNA). No admin rights:
 * a process the user may not inspect simply gives "unknown". Safe to call from any thread.
 */
object WindowsSampler {
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** One reading stamped with [now]. Never throws. */
    fun read(now: Long = System.currentTimeMillis()): Sample = try {
        if (!windows) Sample("unknown", "", 0, now) else readWindows(now)
    } catch (e: Throwable) {
        Sample("unknown", "", 0, now)
    }

    private fun idleMs(): Long {
        val info = WinUser.LASTINPUTINFO()
        if (!User32.INSTANCE.GetLastInputInfo(info)) return 0
        val tick = Kernel32.INSTANCE.GetTickCount().toLong() and 0xFFFFFFFFL
        return (tick - (info.dwTime.toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
    }

    private fun readWindows(now: Long): Sample {
        val idle = idleMs()
        val hwnd: WinDef.HWND = User32.INSTANCE.GetForegroundWindow() ?: return Sample("unknown", "", idle, now)
        val titleBuffer = CharArray(1024)
        val length = User32.INSTANCE.GetWindowText(hwnd, titleBuffer, titleBuffer.size)
        val title = if (length > 0) String(titleBuffer, 0, length) else ""
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        val exe = exeName(pid.value) ?: return Sample("unknown", title, idle, now)
        return Sample(exe, title, idle, now)
    }

    private fun exeName(pid: Int): String? {
        if (pid == 0) return null
        val handle: WinNT.HANDLE = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) ?: return null
        try {
            val buffer = CharArray(1024)
            val size = IntByReference(buffer.size)
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(handle, 0, buffer, size)) return null
            val path = String(buffer, 0, size.value)
            return path.substringAfterLast('\\').substringAfterLast('/').lowercase().takeIf { it.isNotBlank() }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }
}
