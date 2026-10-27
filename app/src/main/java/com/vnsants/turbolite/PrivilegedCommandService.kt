package com.vnsants.turbolite

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs shell commands inside the Shizuku UserService (shell UID with wireless debugging). */
class PrivilegedCommandService : ICommandService.Stub() {
    override fun execute(command: String, timeoutMillis: Long): String {
        val timeout = timeoutMillis.coerceIn(1_000L, 15_000L)
        var process: Process? = null
        val output = StringBuilder()
        val outputLock = Any()
        val readerFailure = AtomicReference<Exception?>(null)
        try {
            val running = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = running
            val reader = Thread({
                try {
                    running.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            synchronized(outputLock) {
                                val remaining = (MAX_OUTPUT_CHARS - output.length).coerceAtLeast(0)
                                if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                            }
                        }
                    }
                } catch (e: Exception) {
                    readerFailure.set(e)
                }
            }, "turbolite-command-output").apply {
                isDaemon = true
                start()
            }

            val finished = running.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                running.destroyForcibly()
                running.waitFor(1, TimeUnit.SECONDS)
            }
            reader.join(1_000L)
            val result = synchronized(outputLock) { output.toString() }
            val suffix = if (result.length >= MAX_OUTPUT_CHARS) "\n[saída truncada]" else ""
            val detail = readerFailure.get()?.let { "\n[falha ao ler saída: ${it.message ?: "erro"}]" } ?: ""
            val code = if (finished) running.exitValue() else TIMEOUT_EXIT_CODE
            return "$code\n$result$suffix$detail"
        } catch (e: Exception) {
            process?.destroyForcibly()
            val detail = e.message ?: e.javaClass.simpleName
            return "125\nFalha no serviço Shizuku: $detail"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 262_144
        const val TIMEOUT_EXIT_CODE = 124
    }
}
