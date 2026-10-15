package com.example.beast_academy_apk

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Keeps cancellation complete across Compose's minimal synthetic cancel events. */
internal open class BeastAcademyWebView(context: Context, private val repairCancellation: Boolean = true) : WebView(context) {
    private val traceId = Integer.toHexString(System.identityHashCode(this))
    private var activeTouch: MotionEvent? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        DiagnosticTrace.touch("webview-$traceId-dispatch", event)
        // Compose/ViewGroup may cancel with one dummy pointer (id 0), even when several
        // different pointers are active. Vanadium forwards that list to Blink, which then
        // retains the omitted contacts. Preserve all active IDs when ending the stream.
        val previous = activeTouch
        val corrected = if (repairCancellation && event.actionMasked == MotionEvent.ACTION_CANCEL && previous != null &&
            pointerIds(event) != pointerIds(previous)) {
            copyPointers(previous, MotionEvent.ACTION_CANCEL, event.eventTime).also {
                DiagnosticTrace.record("webview-$traceId expanded cancel from=${pointerIds(event)} to=${pointerIds(it)}")
            }
        } else null
        if (event.actionMasked != MotionEvent.ACTION_CANCEL) updateActiveTouch(event)
        return try {
            super.dispatchTouchEvent(corrected ?: event).also {
                DiagnosticTrace.touch("webview-$traceId-return", event, it)
            }
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) {
                activeTouch?.recycle()
                activeTouch = null
            }
            corrected?.recycle()
        }
    }

    private fun pointerIds(event: MotionEvent): Set<Int> =
        (0 until event.pointerCount).mapTo(mutableSetOf()) { event.getPointerId(it) }

    private fun updateActiveTouch(event: MotionEvent) {
        // Physical fingers may continue moving/lifting after a focus/cover cancellation.
        // Those events do not begin another browser stream; wait for a new DOWN.
        if (activeTouch == null && event.actionMasked != MotionEvent.ACTION_DOWN) return
        if (event.actionMasked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP)) return
        val next = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (event.pointerCount > 1) copyPointers(event, MotionEvent.ACTION_MOVE, event.eventTime, event.actionIndex) else null
        } else MotionEvent.obtain(event)
        activeTouch?.recycle()
        activeTouch = next
    }

    private fun copyPointers(event: MotionEvent, action: Int, time: Long, exclude: Int = -1): MotionEvent {
        val indices = (0 until event.pointerCount).filter { it != exclude }
        val properties = indices.map { index -> MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) } }.toTypedArray()
        val coords = indices.map { index -> MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) } }.toTypedArray()
        return MotionEvent.obtain(event.downTime, maxOf(event.eventTime, time), action, indices.size,
            properties, coords, event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags)
    }

    fun cancelActiveTouches(reason: String) {
        if (!repairCancellation) return // Instrumented baseline comparison only; production defaults true.
        val previous = activeTouch ?: return
        val cancel = copyPointers(previous, MotionEvent.ACTION_CANCEL, android.os.SystemClock.uptimeMillis())
        DiagnosticTrace.record("webview-$traceId cancel active reason=$reason ids=${pointerIds(cancel)}")
        try { dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        DiagnosticTrace.touch("webview-$traceId-onTouch", event)
        return super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        DiagnosticTrace.record("webview-$traceId focus=$hasWindowFocus")
        if (!hasWindowFocus) cancelActiveTouches("focus lost")
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        DiagnosticTrace.record("webview-$traceId detached")
        cancelActiveTouches("detached")
        super.onDetachedFromWindow()
    }

    fun installInputDiagnostics() {
        if (!BuildConfig.DEBUG) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DiagnosticTrace.record("webview-$traceId DOM diagnostics unavailable")
            return
        }
        val origins = setOf("https://beastacademy.com", "https://*.beastacademy.com", "https://inputlab.invalid")
        WebViewCompat.addWebMessageListener(this, "BeastInputTrace", origins) { _, message, origin, mainFrame, _ ->
            DiagnosticTrace.record("dom webview=$traceId origin=$origin main=$mainFrame ${message.data?.take(3072)}")
            if (mainFrame && activeTouch == null) {
                runCatching {
                    val event = org.json.JSONObject(message.data ?: "{}")
                    if (event.optString("type") in setOf("touchend", "touchcancel") &&
                        event.optJSONArray("touches")?.length()?.let { it > 0 } == true) {
                        DiagnosticTrace.preserveFailure("webview=$traceId DOM contacts remain after native stream ended")
                    }
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(this, INPUT_TRACE_SCRIPT, origins)
    }
}

internal class InputTraceHost(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        DiagnosticTrace.touch("host-dispatch", event)
        val handled = super.dispatchTouchEvent(event)
        DiagnosticTrace.touch("host-return", event, handled)
        return handled
    }
}

private val INPUT_TRACE_SCRIPT = """
    (() => {
      if (window.__beastInputTraceInstalled) return;
      window.__beastInputTraceInstalled = true;
      const lastMove = {};
      const touches = list => Array.from(list || [], t => ({id:t.identifier,x:t.clientX,y:t.clientY,target:t.target.tagName}));
      function record(event) {
        if (event.type.endsWith('move')) {
          if (event.timeStamp - (lastMove[event.type] || -1000) < 80) return;
          lastMove[event.type] = event.timeStamp;
        }
        const data = {type:event.type,wall:Date.now(),time:event.timeStamp,hidden:document.hidden,
          trusted:event.isTrusted,cancelable:event.cancelable,target:event.target?.tagName,
          touches:touches(event.touches),changed:touches(event.changedTouches),
          pointer:event.pointerId,pointerType:event.pointerType,x:event.clientX,y:event.clientY};
        try { BeastInputTrace.postMessage(JSON.stringify(data)); } catch (_) {}
      }
      for (const type of ['touchstart','touchmove','touchend','touchcancel','pointerdown','pointermove',
          'pointerup','pointercancel','visibilitychange']) {
        document.addEventListener(type, record, {capture:true,passive:true});
      }
      window.addEventListener('blur',record,{passive:true});
      window.addEventListener('focus',record,{passive:true});
    })();
""".trimIndent()
