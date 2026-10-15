package com.example.beast_academy_apk

import android.os.Bundle
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DiagnosticTrace.initialize(this)
        WebView.setWebContentsDebuggingEnabled(true)
        webView = BeastAcademyWebView(this, intent.getBooleanExtra("repairCancel", true)).apply {
            settings.javaScriptEnabled = true
            installInputDiagnostics()
            loadDataWithBaseURL("https://inputlab.invalid/", FIXTURE, "text/html", "UTF-8", null)
        }
        if (intent.getBooleanExtra("nativeHost", false)) {
            setContentView(makeHost())
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
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
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
