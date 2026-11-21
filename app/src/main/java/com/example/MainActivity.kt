package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    createNotificationChannel()
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(dynamicColor = false) {
        BillZoAppScreen(onExitApp = { finish() })
      }
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelId = getString(R.string.notification_channel_id)
      val name = getString(R.string.notification_channel_name)
      val descriptionText = getString(R.string.notification_channel_desc)
      val channel =
        NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = descriptionText
        }
      val notificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }
}

private const val LOCAL_APP_URL = "https://localhost/billzo.html"

private fun requiredAppPermissions(): List<String> = buildList {
  add(Manifest.permission.CAMERA)
  add(Manifest.permission.RECORD_AUDIO)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    add(Manifest.permission.POST_NOTIFICATIONS)
  }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
  return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun isNotificationAllowed(context: Context): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
  } else {
    NotificationManagerCompat.from(context).areNotificationsEnabled()
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BillZoAppScreen(onExitApp: () -> Unit) {
  val context = LocalContext.current
  val activity = context as? Activity
  val lifecycleOwner = LocalLifecycleOwner.current

  var cameraGranted by remember {
    mutableStateOf(isPermissionGranted(context, Manifest.permission.CAMERA))
  }
  var micGranted by remember {
    mutableStateOf(isPermissionGranted(context, Manifest.permission.RECORD_AUDIO))
  }
  var notificationsGranted by remember { mutableStateOf(isNotificationAllowed(context)) }
  var hasRequestedInitial by remember { mutableStateOf(false) }
  var bannerDismissed by remember { mutableStateOf(false) }

  val webViewHolder = remember { mutableStateOf<WebView?>(null) }
  var pendingWebPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
  var pendingFilePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

  fun refreshPermissions() {
    cameraGranted = isPermissionGranted(context, Manifest.permission.CAMERA)
    micGranted = isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
    notificationsGranted = isNotificationAllowed(context)
  }

  val allRuntimeGranted = cameraGranted && micGranted && notificationsGranted

  val startupPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
      hasRequestedInitial = true
      refreshPermissions()
    }

  val webPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
      refreshPermissions()
      val request = pendingWebPermissionRequest
      pendingWebPermissionRequest = null
      if (request != null) {
        val grantedResources =
          request.resources.filter { resource ->
            when (resource) {
              PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                isPermissionGranted(context, Manifest.permission.CAMERA)
              PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
              else -> true
            }
          }
        if (grantedResources.isNotEmpty()) {
          request.grant(grantedResources.toTypedArray())
        } else {
          request.deny()
        }
      }
    }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
      refreshPermissions()
      val status = if (granted || isNotificationAllowed(context)) "granted" else "denied"
      webViewHolder.value?.evaluateJavascript(
        "window.__bzOnNotifyPerm && window.__bzOnNotifyPerm('$status');",
        null,
      )
    }

  val fileChooserLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val callback = pendingFilePathCallback
      pendingFilePathCallback = null
      if (callback != null) {
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
      }
    }

  LaunchedEffect(Unit) {
    val missing = requiredAppPermissions().filter { !isPermissionGranted(context, it) }
    if (missing.isNotEmpty()) {
      startupPermissionLauncher.launch(missing.toTypedArray())
    } else {
      hasRequestedInitial = true
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          refreshPermissions()
          webViewHolder.value?.onResume()
        }
        Lifecycle.Event.ON_PAUSE -> {
          webViewHolder.value?.onPause()
        }
        Lifecycle.Event.ON_DESTROY -> {
          pendingFilePathCallback?.onReceiveValue(null)
          pendingFilePathCallback = null
          pendingWebPermissionRequest?.deny()
          pendingWebPermissionRequest = null
          webViewHolder.value?.destroy()
          webViewHolder.value = null
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler {
    val webView = webViewHolder.value
    if (webView == null) {
      onExitApp()
      return@BackHandler
    }
    webView.evaluateJavascript(
      """
      (function() {
        if (typeof MODALS !== 'undefined' && MODALS.length) {
          MODALS[MODALS.length - 1].close();
          return 'handled';
        }
        var side = document.getElementById('side');
        if (side && side.classList.contains('open') && typeof toggleSide === 'function') {
          toggleSide(false);
          return 'handled';
        }
        var bk = document.getElementById('bk');
        if (bk && typeof S !== 'undefined' && !S.user) {
          bk.click();
          return 'handled';
        }
        if (typeof S !== 'undefined' && S.user && S.route && S.route !== 'dashboard' && typeof go === 'function') {
          go('dashboard');
          return 'handled';
        }
        return 'exit';
      })()
      """.trimIndent()
    ) { result ->
      if (result == "\"exit\"" || result == "null") {
        if (webView.canGoBack()) {
          webView.goBack()
        } else {
          onExitApp()
        }
      }
    }
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize().testTag("billzo_webview"),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          val wv = this
          webViewHolder.value = wv

          CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
          }

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
          }

          addJavascriptInterface(
            BillZoAndroidBridge(
              context = ctx,
              webView = this,
              onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                  val status = if (isNotificationAllowed(ctx)) "granted" else "denied"
                  post {
                    evaluateJavascript(
                      "window.__bzOnNotifyPerm && window.__bzOnNotifyPerm('$status');",
                      null,
                    )
                  }
                }
              },
            ),
            "AndroidBridge",
          )

          setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            when {
              url.startsWith("blob:") -> {
                val safeUrl = JSONObject.quote(url)
                val safeName = JSONObject.quote(fileName)
                val safeMime = JSONObject.quote(mimetype ?: "application/octet-stream")
                evaluateJavascript(
                  """
                  (function() {
                    fetch($safeUrl)
                      .then(function(r) { return r.blob(); })
                      .then(function(b) {
                        var fr = new FileReader();
                        fr.onloadend = function() {
                          if (window.AndroidBridge && fr.result) {
                            window.AndroidBridge.saveBase64File(fr.result, $safeName, b.type || $safeMime);
                          }
                        };
                        fr.readAsDataURL(b);
                      })
                      .catch(function() {});
                  })();
                  """.trimIndent(),
                  null,
                )
              }
              url.startsWith("data:") -> {
                saveDataUriToDevice(ctx, url, fileName, mimetype ?: "application/octet-stream")
              }
              else -> {
                openExternalUri(ctx, Uri.parse(url))
              }
            }
          }

          webViewClient =
            object : WebViewClient() {
              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
              ): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (
                  uri.scheme == "https" &&
                    uri.host == "localhost" &&
                    (uri.path == "/billzo.html" || uri.path == "/")
                ) {
                  return try {
                    WebResourceResponse(
                      "text/html",
                      "UTF-8",
                      200,
                      "OK",
                      mapOf("Cache-Control" to "no-store"),
                      ctx.assets.open("billzo.html"),
                    )
                  } catch (_: Exception) {
                    null
                  }
                }
                return null
              }

              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
              ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "https" && uri.host == "localhost") {
                  return false
                }
                openExternalUri(ctx, uri)
                return true
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                  """
                  (function() {
                    if (window.__bzAndroidHooked) return;
                    window.__bzAndroidHooked = true;
                    window.print = function() {
                      if (window.AndroidBridge) window.AndroidBridge.printPage();
                    };
                    var resolvers = [];
                    window.__bzOnNotifyPerm = function(status) {
                      try { AndroidNotification.permission = status; } catch (e) {}
                      while (resolvers.length) {
                        var fn = resolvers.shift();
                        try { fn(status); } catch (e) {}
                      }
                    };
                    function AndroidNotification(title, options) {
                      this.title = String(title || 'BillZo');
                      this.body = (options && options.body) ? String(options.body) : '';
                      if (window.AndroidBridge) {
                        window.AndroidBridge.showNotification(this.title, this.body);
                      }
                    }
                    Object.defineProperty(AndroidNotification, 'permission', {
                      get: function() {
                        return window.AndroidBridge ? window.AndroidBridge.getNotificationPermission() : 'default';
                      },
                      set: function() {},
                      configurable: true
                    });
                    AndroidNotification.requestPermission = function(cb) {
                      return new Promise(function(resolve) {
                        resolvers.push(function(st) {
                          if (typeof cb === 'function') cb(st);
                          resolve(st);
                        });
                        if (window.AndroidBridge) {
                          window.AndroidBridge.requestNotificationPermission();
                        } else {
                          window.__bzOnNotifyPerm('default');
                        }
                      });
                    };
                    window.Notification = AndroidNotification;
                  })();
                  """.trimIndent(),
                  null,
                )
              }
            }

          webChromeClient =
            object : WebChromeClient() {
              override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val missing = mutableListOf<String>()
                request.resources.forEach { res ->
                  if (
                    res == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                      !isPermissionGranted(ctx, Manifest.permission.CAMERA)
                  ) {
                    missing.add(Manifest.permission.CAMERA)
                  }
                  if (
                    res == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                      !isPermissionGranted(ctx, Manifest.permission.RECORD_AUDIO)
                  ) {
                    missing.add(Manifest.permission.RECORD_AUDIO)
                  }
                }
                if (missing.isEmpty()) {
                  request.grant(request.resources)
                } else {
                  pendingWebPermissionRequest?.deny()
                  pendingWebPermissionRequest = request
                  webPermissionLauncher.launch(missing.distinct().toTypedArray())
                }
              }

              override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingWebPermissionRequest == request) {
                  pendingWebPermissionRequest = null
                }
              }

              override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
              ): Boolean {
                pendingFilePathCallback?.onReceiveValue(null)
                pendingFilePathCallback = filePathCallback
                val intent =
                  try {
                    fileChooserParams?.createIntent()
                      ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                      }
                  } catch (_: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                      addCategory(Intent.CATEGORY_OPENABLE)
                      type = "*/*"
                    }
                  }
                return try {
                  fileChooserLauncher.launch(intent)
                  true
                } catch (_: ActivityNotFoundException) {
                  pendingFilePathCallback?.onReceiveValue(null)
                  pendingFilePathCallback = null
                  false
                }
              }
            }

          loadUrl(LOCAL_APP_URL)
        }
      },
    )

    AnimatedVisibility(
      visible = hasRequestedInitial && !allRuntimeGranted && !bannerDismissed,
      modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
    ) {
      PermissionPromptCard(
        cameraGranted = cameraGranted,
        micGranted = micGranted,
        notificationsGranted = notificationsGranted,
        onGrantClick = {
          val missing = requiredAppPermissions().filter { !isPermissionGranted(context, it) }
          val permanentlyDenied =
            activity != null &&
              missing.isNotEmpty() &&
              missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
          if (missing.isNotEmpty() && !permanentlyDenied) {
            startupPermissionLauncher.launch(missing.toTypedArray())
          } else {
            val settingsIntent =
              Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
              }
            context.startActivity(settingsIntent)
          }
        },
        onDismissClick = { bannerDismissed = true },
      )
    }
  }
}

@Composable
private fun PermissionPromptCard(
  cameraGranted: Boolean,
  micGranted: Boolean,
  notificationsGranted: Boolean,
  onGrantClick: () -> Unit,
  onDismissClick: () -> Unit,
) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth().widthInMax600(),
    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(R.string.permissions_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.permissions_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PermissionPill(
          icon = Icons.Rounded.CameraAlt,
          label = "Camera",
          granted = cameraGranted,
        )
        PermissionPill(
          icon = Icons.Rounded.Mic,
          label = "Mic",
          granted = micGranted,
        )
        PermissionPill(
          icon = Icons.Rounded.NotificationsActive,
          label = "Alerts",
          granted = notificationsGranted,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      ) {
        OutlinedButton(
          onClick = onDismissClick,
          modifier = Modifier.heightIn(min = 48.dp).testTag("dismiss_permissions_button"),
        ) {
          Text(stringResource(R.string.dismiss))
        }
        Button(
          onClick = onGrantClick,
          modifier = Modifier.heightIn(min = 48.dp).testTag("grant_permissions_button"),
        ) {
          Text(stringResource(R.string.grant_permissions))
        }
      }
    }
  }
}

@Composable
private fun PermissionPill(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  granted: Boolean,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      modifier = Modifier.size(16.dp),
      tint =
        if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color =
        if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
    )
  }
}

private fun Modifier.widthInMax600(): Modifier = this.widthIn(max = 600.dp)

private class BillZoAndroidBridge(
  private val context: Context,
  private val webView: WebView,
  private val onRequestNotificationPermission: () -> Unit,
) {
  private val notifyCounter = AtomicInteger(100)

  @JavascriptInterface
  fun printPage() {
    webView.post {
      val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
      if (printManager != null) {
        val jobName = "BillZo Document"
        val adapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
      }
    }
  }

  @JavascriptInterface
  fun getNotificationPermission(): String {
    return if (isNotificationAllowed(context)) "granted" else "default"
  }

  @JavascriptInterface
  fun requestNotificationPermission() {
    webView.post { onRequestNotificationPermission() }
  }

  @JavascriptInterface
  fun showNotification(title: String, body: String) {
    if (!isNotificationAllowed(context)) return
    val launchIntent =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val channelId = context.getString(R.string.notification_channel_id)
    val notification =
      NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    try {
      NotificationManagerCompat.from(context)
        .notify(notifyCounter.incrementAndGet(), notification)
    } catch (_: SecurityException) {
    }
  }

  @JavascriptInterface
  fun saveBase64File(dataUri: String, fileName: String, mimeType: String) {
    saveDataUriToDevice(context, dataUri, fileName, mimeType)
  }
}

private fun saveDataUriToDevice(
  context: Context,
  dataUri: String,
  rawFileName: String,
  rawMimeType: String,
) {
  try {
    val commaIndex = dataUri.indexOf(',')
    if (commaIndex == -1) return
    val header = dataUri.substring(0, commaIndex)
    val base64Part = dataUri.substring(commaIndex + 1)
    val bytes = Base64.decode(base64Part, Base64.DEFAULT)

    val detectedMime =
      header.substringAfter("data:", "").substringBefore(";").ifBlank { rawMimeType }
    val safeName =
      rawFileName
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "billzo_export_${System.currentTimeMillis()}" }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, safeName)
          put(MediaStore.Downloads.MIME_TYPE, detectedMime)
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BillZo")
        }
      val uri =
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        android.os.Handler(context.mainLooper).post {
          Toast.makeText(context, "Saved to Downloads/BillZo/$safeName", Toast.LENGTH_LONG).show()
        }
        return
      }
    }

    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(exportsDir, safeName)
    outFile.writeBytes(bytes)
    val contentUri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = detectedMime
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    android.os.Handler(context.mainLooper).post {
      context.startActivity(Intent.createChooser(shareIntent, "Save or share $safeName"))
    }
  } catch (_: Exception) {
    android.os.Handler(context.mainLooper).post {
      Toast.makeText(context, "Could not save file", Toast.LENGTH_SHORT).show()
    }
  }
}

private fun openExternalUri(context: Context, uri: Uri) {
  try {
    val intent =
      Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
  } catch (_: Exception) {
    Toast.makeText(context, "No app found to open link", Toast.LENGTH_SHORT).show()
  }
}
