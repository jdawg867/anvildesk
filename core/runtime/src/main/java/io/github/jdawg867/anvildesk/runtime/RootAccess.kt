package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.util.concurrent.TimeUnit

sealed interface RootVerification {
    data object Granted : RootVerification
    data class Denied(val reason: String) : RootVerification
}

object RootAccess {
    private val knownSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
    )

    fun findSuBinary(): String? {
        knownSuPaths.firstOrNull { File(it).canExecute() }?.let { return it }

        return System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.map { File(it, "su") }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    fun verify(timeoutSeconds: Long = 20): RootVerification {
        val su = findSuBinary() ?: return RootVerification.Denied("no executable su binary found")

        val process = try {
            ProcessBuilder(su, "-c", "id -u")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return RootVerification.Denied("could not start su: ${error.javaClass.simpleName}")
        }

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            process.destroyForcibly()
            return RootVerification.Denied("root request timed out")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return if (process.exitValue() == 0 && isRootUid(output)) {
            RootVerification.Granted
        } else {
            RootVerification.Denied("su did not return uid 0")
        }
    }

    internal fun isRootUid(output: String): Boolean =
        output.lineSequence().map(String::trim).any { it == "0" }
}
