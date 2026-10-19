package com.example.beast_academy_apk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileNotFoundException

/** Read-only ADB export, protected by the system/shell DUMP permission in the manifest. */
class DiagnosticExportProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        DiagnosticTrace.initialize(checkNotNull(context))
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/snapshot") throw FileNotFoundException("Read /snapshot only")
        val bytes = DiagnosticTrace.snapshotArchive()
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
        }, "input-trace-export").start()
        return pipe[0]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not inherit openFile's read permission enforcement.
        // Always check the actual Binder caller explicitly for this privileged control.
        checkNotNull(context).enforceCallingPermission(android.Manifest.permission.DUMP, "ADB diagnostics require DUMP")
        val enabled = when (method) {
            "enable-webview-debugging" -> true
            "disable-webview-debugging" -> false
            else -> throw IllegalArgumentException("Unknown diagnostic method")
        }
        val ready = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                if (!abandoned.get()) {
                    WebView.setWebContentsDebuggingEnabled(enabled)
                    DiagnosticTrace.record("ADB diagnostics webviewDebugging=$enabled")
                }
            } catch (error: Throwable) { failure = error }
            finally { ready.countDown() }
        }
        if (!ready.await(3, TimeUnit.SECONDS)) {
            abandoned.set(true)
            throw IllegalStateException("UI thread did not respond; debugging state not confirmed")
        }
        failure?.let { throw IllegalStateException("Cannot change WebView debugging", it) }
        return Bundle().apply {
            putBoolean("enabled", enabled)
            putInt("pid", android.os.Process.myPid())
        }
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
