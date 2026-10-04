package org.readera.openreadera.sync

import java.io.OutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Shared across UI/worker instances. A selection change invalidates all outstanding work. */
internal object DriveAccountGuard {
    private val lock = java.lang.Object()
    private val identityLock = Any()
    private var generation = 0L
    private val jobs = mutableSetOf<Job>()
    private val calls = mutableSetOf<Call>()
    private val outputs = mutableSetOf<OutputStream>()
    private val outputCloser = Executors.newCachedThreadPool { task ->
        Thread(task, "Drive-output-cancel").apply { isDaemon = true }
    }
    private var transactions = 0
    private var pendingIdentityChanges = 0

    fun <T> locked(action: () -> T): T = synchronized(lock, action)

    /** Serialize invalidation, transaction rollback, and publication as one transition. */
    fun <T> updateIdentity(
        expected: Session? = null,
        invalidateWhen: () -> Boolean = { true },
        action: (Session) -> T
    ): T {
        locked { pendingIdentityChanges++ }
        try {
            return synchronized(identityLock) {
                val cancelled = locked {
                    expected?.checkCurrent()
                    if (invalidateWhen()) {
                        generation++
                        Triple(jobs.toList(), calls.toList(), outputs.toList()).also {
                            jobs.clear()
                            calls.clear()
                            outputs.clear()
                        }
                    } else null
                }
                // Neither cancellation callbacks nor a blocked stream close may own the state monitor.
                cancelled?.let { (oldJobs, oldCalls, oldOutputs) ->
                    oldJobs.forEach { it.cancel(CancellationException("Drive identity changed")) }
                    oldCalls.forEach(Call::cancel)
                    oldOutputs.forEach { output ->
                        outputCloser.execute { try { output.close() } catch (_: Exception) {} }
                    }
                }
                locked {
                    // Wait only for local Room transactions to finish/roll back, never remote I/O.
                    if (cancelled != null) while (transactions > 0) lock.wait()
                    action(Session(generation))
                }
            }
        } finally {
            locked { pendingIdentityChanges--; lock.notifyAll() }
        }
    }

    fun invalidate() = updateIdentity { }

    fun capture(): Session = locked {
        if (pendingIdentityChanges > 0) throw CancellationException("Drive identity changed")
        Session(generation)
    }

    class Session internal constructor(private val expected: Long) {
        fun checkCurrent() = locked {
            if (expected != generation) throw CancellationException("Drive identity changed")
        }

        fun <T> commit(action: () -> T): T = locked {
            checkCurrent()
            action()
        }

        fun attach(job: Job) = commit { jobs.add(job) }
        fun detach(job: Job) = locked { jobs.remove(job) }

        suspend fun <T> transaction(action: suspend () -> T): T {
            commit { transactions++ }
            return try {
                action()
            } finally {
                locked { transactions--; lock.notifyAll() }
            }
        }

        /** Register the stream for identity cancellation; do blocking writes outside the monitor. */
        fun <T> withOutput(output: OutputStream, job: Job?, action: (OutputStream) -> T): T {
            fun checkActive() {
                job?.ensureActive()
                checkCurrent()
            }
            checkActive()
            commit { outputs.add(output) }
            val guarded = object : OutputStream() {
                override fun write(value: Int) {
                    checkActive()
                    output.write(value)
                    checkActive()
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (offset < 0 || length < 0 || offset > buffer.size - length) {
                        throw IndexOutOfBoundsException()
                    }
                    checkActive()
                    var position = offset
                    val end = offset + length
                    while (position < end) {
                        checkActive()
                        val count = minOf(64 * 1024, end - position)
                        output.write(buffer, position, count)
                        checkActive()
                        position += count
                    }
                }

                override fun flush() {
                    checkActive()
                    output.flush()
                    checkActive()
                }
            }
            return try {
                action(guarded).also { checkActive() }
            } finally {
                locked { outputs.remove(output) }
            }
        }

        fun execute(client: OkHttpClient, request: Request): Response {
            val call = commit { client.newCall(request).also { calls.add(it) } }
            return try {
                call.execute().also { response ->
                    try { checkCurrent() } catch (failure: Exception) { response.close(); throw failure }
                }
            } finally {
                locked { calls.remove(call) }
            }
        }
    }
}
