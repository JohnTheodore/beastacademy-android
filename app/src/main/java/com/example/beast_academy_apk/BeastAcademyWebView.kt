package com.example.beast_academy_apk

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Owns complete native touch streams at the browser boundary, including interruption. */
internal open class BeastAcademyWebView(context: Context, private val repairCancellation: Boolean = true) : WebView(context) {
    private val traceId = Integer.toHexString(System.identityHashCode(this))
    private var activeTouch: MotionEvent? = null
    private var nativeInputVersion = 0L
    private var lastSuspectedDocument: Double? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        nativeInputVersion++
        DiagnosticTrace.touch("webview-$traceId-dispatch", event)
        if (repairCancellation) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // A fresh stream is also a boundary if an upstream owner omitted its cancel.
                cancelActiveTouches("replaced by new down")
                parent?.requestDisallowInterceptTouchEvent(true)
            } else if (activeTouch == null && event.actionMasked in setOf(
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                // Fingers may still move after focus/cover cancellation. Forwarding these
                // tails can recreate contacts in Blink without a corresponding DOWN.
                DiagnosticTrace.touch("webview-$traceId-orphan-dropped", event)
                return true
            }
        }
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
                parent?.requestDisallowInterceptTouchEvent(false)
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

    private fun checkForStrandedContacts() {
        val version = nativeInputVersion
        // Web messages are asynchronous. An earlier, valid POINTER_UP report can arrive
        // after the native final UP. Inspect the latest DOM state after the stream settles.
        postDelayed({
            if (nativeInputVersion != version || activeTouch != null || !isAttachedToWindow) return@postDelayed
            evaluateJavascript("JSON.stringify(window.__beastInputTraceLastTouch || null)") { result ->
                if (nativeInputVersion != version || activeTouch != null) return@evaluateJavascript
                runCatching {
                    val decoded = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching
                    val latest = org.json.JSONObject(decoded)
                    val document = latest.getDouble("document")
                    if (latest.getJSONArray("touches").length() > 0 && lastSuspectedDocument != document) {
                        lastSuspectedDocument = document
                        DiagnosticTrace.preserveFailure("webview=$traceId document=$document DOM contacts remain after native stream settled")
                    }
                }
            }
        }, 100L)
    }

    fun installInputDiagnostics() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DiagnosticTrace.record("webview-$traceId DOM diagnostics unavailable")
            return
        }
        val origins = setOf("https://beastacademy.com", "https://*.beastacademy.com", "https://inputlab.invalid")
        WebViewCompat.addWebMessageListener(this, "BeastInputTrace", origins) { _, message, origin, mainFrame, _ ->
            DiagnosticTrace.record("dom webview=$traceId origin=$origin main=$mainFrame ${message.data?.take(3072)}")
            if (mainFrame && activeTouch == null) {
                val data = runCatching { org.json.JSONObject(message.data ?: "{}") }.getOrNull()
                if (data?.optString("type") in setOf("touchend", "touchcancel") &&
                    (data?.optJSONArray("touches")?.length() ?: 0) > 0) {
                    checkForStrandedContacts()
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
      const debug = ${BuildConfig.DEBUG};
      const lastMove = {};
      const touches = list => Array.from(list || [], t => ({id:t.identifier,x:t.clientX,y:t.clientY,force:t.force,radiusX:t.radiusX,radiusY:t.radiusY,target:t.target.tagName}));
      function record(event) {
        if (event.type.endsWith('move')) {
          if (event.type !== 'touchmove' && event.timeStamp - (lastMove[event.type] || -1000) < 80) return;
          lastMove[event.type] = event.timeStamp;
        }
        const data = {type:event.type,document:performance.timeOrigin,wall:Date.now(),time:event.timeStamp,hidden:document.hidden,
          trusted:event.isTrusted,cancelable:event.cancelable,target:event.target?.tagName,
          touches:touches(event.touches),changed:touches(event.changedTouches),
          pointer:event.pointerId,pointerType:event.pointerType,x:event.clientX,y:event.clientY};
        if (event.type.startsWith('touch')) window.__beastInputTraceLastTouch = data;
        try { BeastInputTrace.postMessage(JSON.stringify(data)); } catch (_) {}
      }
      for (const type of ['touchstart','touchmove','touchend','touchcancel','pointerdown','pointermove',
          'pointerup','pointercancel','visibilitychange']) {
        if (!debug && type.startsWith('pointer')) continue;
        document.addEventListener(type, record, {capture:true,passive:true});
      }
      window.addEventListener('blur',record,{passive:true});
      window.addEventListener('focus',record,{passive:true});
      try { BeastInputTrace.postMessage(JSON.stringify({type:'document',document:performance.timeOrigin,
        wall:Date.now(),time:performance.now(),viewport:[innerWidth,innerHeight],scale:devicePixelRatio})); } catch (_) {}
    })();
""".trimIndent()
