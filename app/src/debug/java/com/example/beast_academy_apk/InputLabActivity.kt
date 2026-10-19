package com.example.beast_academy_apk

import android.os.Bundle
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import androidx.webkit.TracingController
import androidx.webkit.TracingConfig
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Local, account-free input fixture. Run with -PinputLab=true to isolate the process/data. */
class InputLabActivity : ComponentActivity() {
    internal lateinit var webView: BeastAcademyWebView
    var showHost by mutableStateOf(true)
    internal var nativeChrome: BrowserChrome? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DiagnosticTrace.initialize(this)
        if (intent.getBooleanExtra("chromeTrace", false)) {
            TracingController.getInstance().start(TracingConfig.Builder()
                .addCategories("input", "input.scrolling", "latencyInfo", "benchmark", "renderer", "disabled-by-default-input")
                .setTracingMode(TracingConfig.RECORD_CONTINUOUSLY).build())
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView = BeastAcademyWebView(this, intent.getBooleanExtra("repairCancel", true)).apply {
            settings.javaScriptEnabled = true
            if (intent.getBooleanExtra("lessonLike", false)) {
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
            }
            installInputDiagnostics()
            if (intent.getBooleanExtra("claimCanvas", false)) {
                check(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                val policy = assets.open("puzzle-touch-policy.js").bufferedReader().use { it.readText() }
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,
                    "($policy)({selector:'canvas'});", setOf("https://inputlab.invalid"))
            }
            var fixture = if (intent.getBooleanExtra("scrollable", false)) {
                FIXTURE.replace("touch-action:none;overflow:hidden", "touch-action:auto;overflow:auto")
                    .replace("<canvas></canvas>", "<canvas style=\"height:2400px\"></canvas>")
            } else FIXTURE
            if (intent.getBooleanExtra("lessonLike", false)) {
                fixture = FIXTURE.replace("touch-action:none;overflow:hidden", "touch-action:auto;overflow:hidden")
                    .replace("initial-scale=1", "initial-scale=1,maximum-scale=1")
                    .replace("<canvas></canvas>", "<canvas style=\"position:absolute;left:234.32px;top:190.18px;width:810px;height:461px\"></canvas>")
                    .replace("window.probe=", """
                      const canvas=document.querySelector('canvas');
                      for(const type of ['touchstart','touchmove','touchend']) canvas.addEventListener(type,e=>{
                        if(e.touches.length>1)return;
                        if(type!=='touchstart')e.preventDefault();
                      },{passive:false});
                      window.probe=
                    """.trimIndent())
            }
            loadDataWithBaseURL("https://inputlab.invalid/", fixture, "text/html", "UTF-8", null)
        }
        if (intent.getBooleanExtra("nativeHost", false)) {
            val chrome = BrowserChrome(this) {}
            nativeChrome = chrome
            chrome.browserHost.addView(webView, ViewGroup.LayoutParams(-1, -1))
            setContentView(chrome)
        } else {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    if (showHost) AndroidView(factory = { makeHost() }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun makeHost(): InputTraceHost = InputTraceHost(this).apply {
        (webView.parent as? ViewGroup)?.removeView(webView)
        addView(webView, ViewGroup.LayoutParams(-1, -1))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        DiagnosticTrace.touch("lab-activity", event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        if (intent.getBooleanExtra("chromeTrace", false) && TracingController.getInstance().isTracing) stopChromeTrace {}
        nativeChrome?.dispose()
        webView.cancelActiveTouches("lab destroyed")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    fun stopChromeTrace(onFinished: () -> Unit) {
        val output = object : FileOutputStream(File(filesDir, "chrome-input-trace.json")) {
            override fun close() { super.close(); onFinished() }
        }
        if (!TracingController.getInstance().stop(output) { it.run() }) output.close()
    }

    companion object {
        private const val FIXTURE = """
          <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
          <style>html,body{margin:0;height:100%;touch-action:none;overflow:hidden}canvas{width:100%;height:100%;background:#d8e8ef}</style>
          <canvas></canvas><script>
          window.probe={active:[],events:[],dragMoves:0,cancels:0};
          for(const type of ['touchstart','touchmove','touchend','touchcancel']) {
            document.addEventListener(type,e=>{
              probe.active=Array.from(e.touches,t=>t.identifier);
              probe.events.push({type,active:probe.active.slice(),changed:Array.from(e.changedTouches,t=>t.identifier)});
              if(probe.events.length>200)probe.events.shift();
              if(type==='touchmove'&&e.touches.length===1)probe.dragMoves++;
              if(type==='touchcancel')probe.cancels++;
            },{passive:true});
          }
          </script>
        """
    }
}
