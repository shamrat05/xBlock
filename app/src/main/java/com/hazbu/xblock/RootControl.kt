package com.hazbu.xblock

/** Root-backed switch for both filtering layers. */
object RootControl {
    private const val VECTOR = "/data/adb/modules/zygisk_vector/cli"
    private const val HOST_SOURCE = "/data/adb/modules/root-hosts-blocker/system/etc/hosts"
    private const val HOST_TARGET = "/system/etc/hosts"
    private const val OFF_MARKER = "/data/adb/root-hosts-blocker.off"
    private const val XBLOCK = "com.hazbu.xblock"
    private const val LEGACY = "tw.fatminmin.xposed.minminguard.next"

    data class Status(
        val rootAvailable: Boolean,
        val enabled: Boolean,
        val details: String,
    )

    private fun runRoot(command: String): ExecResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            ExecResult(code == 0, output, error)
        } catch (e: Exception) {
            ExecResult(false, "", e.message ?: "Root command failed")
        }
    }

    data class ExecResult(val success: Boolean, val output: String, val error: String)

    fun setEnabled(enabled: Boolean): ExecResult {
        val host = if (enabled) {
            "rm -f '$OFF_MARKER'; " +
                "if ! grep -q 'root-hosts-blocker.*/system/etc/hosts' /proc/self/mountinfo; " +
                "then mount -o bind '$HOST_SOURCE' '$HOST_TARGET'; fi"
        } else {
            "touch '$OFF_MARKER'; umount '$HOST_TARGET' 2>/dev/null || true"
        }
        val modules = if (enabled) {
            "$VECTOR modules enable $XBLOCK; $VECTOR modules disable $LEGACY"
        } else {
            "$VECTOR modules disable $XBLOCK; $VECTOR modules disable $LEGACY"
        }
        return runRoot("$host; $modules")
    }

    fun status(): Status {
        val result = runRoot(
            "if [ -e '$OFF_MARKER' ]; then echo HOST_OFF; " +
                "elif grep -q 'root-hosts-blocker.*/system/etc/hosts' /proc/self/mountinfo; " +
                "then echo HOST_ON; else echo HOST_OFF; fi; " +
                "$VECTOR modules ls"
        )
        if (!result.success) {
            return Status(false, false, result.error.ifBlank { "Root command failed" })
        }
        val output = result.output
        val hostOn = output.contains("HOST_ON")
        val moduleOn = output.lineSequence().any {
            it.trim().startsWith("$XBLOCK ") && it.contains("enabled")
        }
        return Status(true, hostOn && moduleOn, if (hostOn && moduleOn) "ON" else "OFF")
    }
}
