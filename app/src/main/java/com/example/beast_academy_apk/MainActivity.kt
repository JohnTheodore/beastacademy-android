package com.example.beast_academy_apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.beast_academy_apk.ui.theme.BeastacademyapkTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        enableEdgeToEdge()
        setContent {
            BeastacademyapkTheme {
                BeastAcademyApp()
            }
        }
    }
}

@Composable
fun BeastAcademyApp() {
    val activity = LocalActivity.current as MainActivity
    val lifecycle = activity.lifecycle

    var showTtsWarning by remember { mutableStateOf(false) }
    var recoveryState by remember { mutableStateOf<BeastAcademyWebViewController.RecoveryState>(BeastAcademyWebViewController.RecoveryState.Healthy) }
    var loadProgress by remember { mutableIntStateOf(0) }

    val speechBridge = remember {
        AndroidSpeechSynthesis(context = activity) {
            showTtsWarning = true
        }
    }

    // Initialize controller without launcher first
    val controller = remember {
        BeastAcademyWebViewController(
            activity = activity,
            speechBridge = speechBridge,
            onProgressChanged = { loadProgress = it },
            onRecoveryStateChanged = { recoveryState = it }
        )
    }

    // Link file picker launcher to controller
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        controller.filePathCallback?.onReceiveValue(uris.toTypedArray())
        controller.filePathCallback = null
    }
    
    // Pass the launcher to the controller
    SideEffect {
        controller.filePickerLauncher = filePickerLauncher
    }

    DisposableEffect(activity, controller) {
        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    controller.onScreenOff()
                }
            }
        }

        ContextCompat.registerReceiver(
            activity,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching { activity.unregisterReceiver(screenOffReceiver) }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.shutdown()
        }
    }

    if (showTtsWarning) {
        LaunchedEffect(Unit) {
            delay(8000L)
            showTtsWarning = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                FrameLayout(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    controller.attachHost(this)
                }
            },
            onRelease = { host ->
                controller.detachHost(host)
            }
        )

        if (loadProgress in 1..99 && recoveryState == BeastAcademyWebViewController.RecoveryState.Healthy) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Recovery UI Overlay
        when (val state = recoveryState) {
            is BeastAcademyWebViewController.RecoveryState.Healthy -> {}
            is BeastAcademyWebViewController.RecoveryState.HungWaiting,
            is BeastAcademyWebViewController.RecoveryState.Recovering -> {
                RecoveryOverlay(
                    message = if (state is BeastAcademyWebViewController.RecoveryState.HungWaiting) 
                        "Beast Academy stopped responding." else "Recovering...",
                    showProgress = true,
                    onReload = { controller.requestRecovery(RecoveryReason.MANUAL_RELOAD) }
                )
            }
            is BeastAcademyWebViewController.RecoveryState.Failed -> {
                RecoveryOverlay(
                    message = state.message,
                    showProgress = false,
                    onReload = { controller.requestRecovery(RecoveryReason.MANUAL_RELOAD) }
                )
            }
        }

        if (showTtsWarning) {
            TtsWarningOverlay()
        }
    }

    BackHandler {
        if (!controller.handleBack()) {
            activity.finish()
        }
    }
}

@Composable
fun RecoveryOverlay(message: String, showProgress: Boolean, onReload: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    textAlign = TextAlign.Center
                )
                
                if (showProgress) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onReload,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reload now")
                }
            }
        }
    }
}

@Composable
fun TtsWarningOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.7f)
            ),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = "No TTS engine found. Go to Settings → Accessibility → Text-to-speech to install one.",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
