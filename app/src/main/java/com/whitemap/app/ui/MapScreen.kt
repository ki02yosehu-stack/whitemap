package com.whitemap.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.data.SavedCamera
import com.whitemap.app.location.CurrentLocation
import com.whitemap.app.location.TrackingService
import com.whitemap.app.location.hasLocationPermission
import com.whitemap.app.location.requestLocationUpdates
import com.whitemap.app.util.TrackGeometry
import com.whitemap.app.util.WorldPoint
import kotlinx.coroutines.launch

private val locationPermissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val roads by vm.roads.collectAsStateWithLifecycle()
    val landmarks by vm.landmarks.collectAsStateWithLifecycle()
    val tripCount by vm.tripCount.collectAsStateWithLifecycle()
    val projection by vm.projection.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val location by vm.currentLocation.collectAsStateWithLifecycle()

    val camera = remember {
        val saved = vm.savedCamera
        MapCamera(saved?.x ?: 0.0, saved?.y ?: 0.0, saved?.scale ?: with(density) { 1.2.dp.toPx() })
    }
    var follow by rememberSaveable { mutableStateOf(vm.savedCamera?.follow ?: true) }

    var hasPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    var startAfterGrant by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = context.hasLocationPermission()
        if (startAfterGrant && hasPermission) TrackingService.start(context)
        startAfterGrant = false
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(locationPermissions)
    }

    // 画面を見ている間は現在地を表示する（記録していなくても）
    LifecycleStartEffect(hasPermission) {
        val stop = context.requestLocationUpdates(3_000, 0f) { CurrentLocation.update(it) }
        onStopOrDispose { stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.saveCamera(SavedCamera(camera.x, camera.y, camera.scale, follow))
    }

    // 追従モード：現在地が動いたら地図もついていく
    LaunchedEffect(follow, location, projection) {
        val loc = location ?: return@LaunchedEffect
        val proj = projection ?: return@LaunchedEffect
        if (follow) camera.animateTo(proj.x(loc.longitude), proj.y(loc.latitude))
    }

    var newLandmarkAt by remember { mutableStateOf<WorldPoint?>(null) }
    var editing by remember { mutableStateOf<LandmarkEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = vm.exportGpx(uri)
            snackbar.showSnackbar(if (ok) "GPXファイルに書き出しました" else "書き出しに失敗しました")
        }
    }

    Box(Modifier.fillMaxSize().background(MapColors.Paper)) {
        WhiteMapCanvas(
            camera = camera,
            roads = roads,
            landmarks = landmarks,
            projection = projection,
            currentLocation = location,
            onUserGesture = { follow = false },
            onLongPress = { if (projection != null) newLandmarkAt = it },
            onLandmarkTap = { editing = it },
            modifier = Modifier.fillMaxSize(),
        )

        // 何もないときのひとこと
        val hint = when {
            !hasPermission -> "位置情報を許可すると、\n地図づくりを始められます。"
            projection == null -> "現在地をさがしています…"
            roads.isEmpty && !isRecording -> "まだ、なにもない。\n歩いた道が、あなたの地図になる。"
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                color = Color(0xFF9A9A9A),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
            )
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            // 上：記録のまとめとメニュー
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                StatsCard(
                    meters = roads.totalMeters,
                    trips = tripCount,
                    landmarks = landmarks.size,
                    recording = isRecording,
                )
                Spacer(Modifier.weight(1f))
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Surface(shape = CircleShape, color = Color.White, shadowElevation = 2.dp) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("GPXで書き出す") },
                            onClick = { menuOpen = false; exportLauncher.launch("whitemap.gpx") },
                        )
                        DropdownMenuItem(
                            text = { Text("地図をまっさらにする") },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                        DropdownMenuItem(
                            text = { Text("使いかた") },
                            onClick = { menuOpen = false; showAbout = true },
                        )
                    }
                }
            }

            // 左下：縮尺
            ScaleBar(camera, Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp))

            // 右下：現在地
            SmallFloatingActionButton(
                onClick = {
                    if (!hasPermission) permissionLauncher.launch(locationPermissions) else follow = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 76.dp),
                containerColor = Color.White,
                contentColor = if (follow) MapColors.Me else MapColors.InkSoft,
            ) {
                Icon(
                    if (follow) Icons.Filled.MyLocation else Icons.Outlined.LocationSearching,
                    contentDescription = "現在地",
                )
            }

            // 下：記録ボタン
            ExtendedFloatingActionButton(
                onClick = {
                    when {
                        isRecording -> TrackingService.stop(context)
                        hasPermission -> {
                            follow = true
                            TrackingService.start(context)
                        }
                        else -> {
                            startAfterGrant = true
                            permissionLauncher.launch(locationPermissions)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                containerColor = if (isRecording) Color.White else MapColors.Ink,
                contentColor = if (isRecording) MapColors.Ink else Color.White,
                icon = {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = if (isRecording) MapColors.Ink else Color(0xFFE05050),
                    )
                },
                text = { Text(if (isRecording) "記録をとめる" else "道をつくる") },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 80.dp))
    }

    newLandmarkAt?.let { at ->
        LandmarkDialog(
            title = "目印を書きこむ",
            initialName = "",
            onConfirm = { name ->
                projection?.let { vm.addLandmark(it.lat(at.y), it.lon(at.x), name) }
                newLandmarkAt = null
            },
            onDismiss = { newLandmarkAt = null },
        )
    }
    editing?.let { lm ->
        LandmarkDialog(
            title = "目印",
            initialName = lm.name,
            onConfirm = { name -> vm.renameLandmark(lm, name); editing = null },
            onDelete = { vm.deleteLandmark(lm); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("地図をまっさらにしますか？") },
            text = { Text("記録したすべての道と目印が消えます。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("消す") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("やめる") } },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("白紙の地図") },
            text = {
                Text(
                    "この地図は、はじめは真っ白です。\n\n" +
                        "・「道をつくる」を押して出かけると、通ったところが道になります（画面を消していても記録します）。\n" +
                        "・同じところを何度も通ると、その道はだんだん濃くなります。\n" +
                        "・地図を長押しすると、そこに目印を書きこめます。目印をタップすると名前の変更・削除ができます。\n" +
                        "・2本指で拡大・縮小、ダブルタップで拡大。\n" +
                        "・記録は端末の中だけに保存されます。メニューからGPXで書き出せます。"
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("とじる") } },
        )
    }
}

@Composable
private fun StatsCard(meters: Double, trips: Int, landmarks: Int, recording: Boolean) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Box(Modifier.size(8.dp).background(Color(0xFFE05050), CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    TrackGeometry.formatDistance(meters),
                    style = MaterialTheme.typography.titleMedium,
                    color = MapColors.Ink,
                )
                Text("  の道", style = MaterialTheme.typography.bodySmall, color = MapColors.InkSoft)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "旅 $trips 回 · 目印 $landmarks",
                style = MaterialTheme.typography.bodySmall,
                color = MapColors.InkSoft,
            )
        }
    }
}

@Composable
private fun ScaleBar(camera: MapCamera, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxPx = with(density) { 96.dp.toPx() }
    val meters = TrackGeometry.niceDistance(maxPx / camera.scale.toDouble())
    val barDp = with(density) { (meters * camera.scale).toFloat().toDp() }
    Column(modifier, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(TrackGeometry.formatDistance(meters), fontSize = 11.sp, color = MapColors.InkSoft)
        Canvas(Modifier.width(barDp).height(6.dp)) {
            val stroke = 1.5.dp.toPx()
            val y = size.height
            drawLine(MapColors.InkSoft, Offset(0f, y), Offset(size.width, y), stroke)
            drawLine(MapColors.InkSoft, Offset(0f, 0f), Offset(0f, y), stroke)
            drawLine(MapColors.InkSoft, Offset(size.width, 0f), Offset(size.width, y), stroke)
        }
    }
}

@Composable
private fun LandmarkDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                placeholder = { Text("例：お気に入りのパン屋") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { "目印" }) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("削除", color = Color(0xFFC04040)) }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
}
