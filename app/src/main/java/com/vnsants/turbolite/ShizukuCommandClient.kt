package com.vnsants.turbolite

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Binds once to a Shizuku UserService and routes blocking work to calling worker threads. */
object ShizukuCommandClient {
    private val lock = Any()
    @Volatile private var remote: ICommandService? = null
    private var connectionFuture: CompletableFuture<ICommandService>? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null

    fun run(context: Context, command: String, timeoutMillis: Long): Pair<Int, String> {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Shizuku não está conectado ou autorizado")
        }
        val service = getService(context.applicationContext)
        val response = try {
            service.execute(command, timeoutMillis)
        } catch (e: RemoteException) {
            remote = null
            throw IllegalStateException("A conexão com o serviço Shizuku foi perdida", e)
        }
        val separator = response.indexOf('\n')
        if (separator < 1) throw IllegalStateException("Resposta inválida do serviço Shizuku")
        val exitCode = response.substring(0, separator).toIntOrNull()
            ?: throw IllegalStateException("Código de saída inválido do serviço Shizuku")
        return exitCode to response.substring(separator + 1)
    }

    private fun getService(context: Context): ICommandService {
        remote?.let { return it }
        val future: CompletableFuture<ICommandService>
        synchronized(lock) {
            remote?.let { return it }
            future = connectionFuture ?: CompletableFuture<ICommandService>().also { pending ->
                connectionFuture = pending
                val serviceArgs = args ?: Shizuku.UserServiceArgs(
                    ComponentName(context, PrivilegedCommandService::class.java)
                ).tag("turbolite-command-service")
                    .version(1)
                    .processNameSuffix("commands")
                    .daemon(false)
                    .also { args = it }
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = ICommandService.Stub.asInterface(binder)
                        if (service == null) {
                            pending.completeExceptionally(IllegalStateException("Binder Shizuku inválido"))
                        } else {
                            remote = service
                            pending.complete(service)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        remote = null
                        synchronized(lock) {
                            connectionFuture?.completeExceptionally(IllegalStateException("Serviço Shizuku desconectado"))
                            connectionFuture = null
                            connection = null
                        }
                    }
                }
                connection = serviceConnection
                try {
                    Shizuku.bindUserService(serviceArgs, serviceConnection)
                } catch (e: Exception) {
                    connectionFuture = null
                    pending.completeExceptionally(e)
                }
            }
        }
        return try {
            future.get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            synchronized(lock) {
                if (connectionFuture === future) connectionFuture = null
            }
            throw TimeoutException("Shizuku não iniciou o serviço privilegiado em tempo hábil")
        }
    }

    private const val BIND_TIMEOUT_SECONDS = 12L
}
