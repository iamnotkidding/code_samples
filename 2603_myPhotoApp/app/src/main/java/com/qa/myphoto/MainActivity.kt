package com.qa.myphoto


import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlin.math.abs

data class GalleryMedia(
    val id: String,
    val uri: Uri,
    val isVideo: Boolean,
    val resolutionText: String,
    val ratio: Float,
    val isWide: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                PermissionCheckAPI34 {
                    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        MainGalleryApp()
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCheckAPI34(content: @Composable () -> Unit) {
    var isGranted by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val imagesGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val videoGranted = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val visualUserSelectedGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
        } else false
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

        isGranted = (imagesGranted && videoGranted) || visualUserSelectedGranted || storageGranted
    }
    
    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionsToRequest.apply {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.apply {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        launcher.launch(permissionsToRequest.toTypedArray())
    }
    
    if (isGranted) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainGalleryApp() {
    val context = LocalContext.current
    val tabs = listOf("전체", "사진", "동영상", "커스텀")
    
    val videoImageLoader = remember { 
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build() 
    }
    
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    
    var columnCount by remember { mutableIntStateOf(3) }
    var isAutoScrollEnabled by remember { mutableStateOf(false) }
    val totalMedia = remember { mutableStateListOf<GalleryMedia>() }
    
    val itemScales = remember { mutableStateMapOf<String, Float>() }

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val localItems = mutableListOf<GalleryMedia>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, 
                MediaStore.MediaColumns.MIME_TYPE, 
                MediaStore.MediaColumns.WIDTH, 
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATE_ADDED
            )
            
            try {
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val wCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val hCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val w = maxOf(1, cursor.getInt(wCol))
                        val h = maxOf(1, cursor.getInt(hCol))
                        val ratio = w.toFloat() / h
                        val isVideo = (cursor.getString(mimeCol) ?: "").startsWith("video")
                        val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        
                        localItems.add(GalleryMedia("local_$id", uri, isVideo, "${w}x${h}", ratio, ratio > 1.3f))
                    }
                }
                withContext(Dispatchers.Main) { totalMedia.addAll(localItems) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { itemScales.clear() }) { 
                        Icon(Icons.Default.Refresh, "재배치 초기화") 
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(onClick = { if (columnCount < 5) columnCount++ }) { Icon(Icons.Default.Remove, "작게") }
                    Text("${columnCount}단 뷰", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { if (columnCount > 1) columnCount-- }) { Icon(Icons.Default.Add, "크게") }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Button(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }) {
                        Text(if (isAutoScrollEnabled) "정지" else "자동")
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState, 
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { pageIdx ->
            val filtered = remember(pageIdx, totalMedia.size) {
                when (pageIdx) {
                    1 -> totalMedia.filter { !it.isVideo }
                    2 -> totalMedia.filter { it.isVideo }
                    3 -> totalMedia.filter { it.isVideo }
                    else -> totalMedia
                }
            }
            
            OptimalReflowGrid(
                items = filtered,
                displayColumns = columnCount,
                itemScales = itemScales, 
                isAutoScroll = isAutoScrollEnabled,
                onManualInteraction = { isAutoScrollEnabled = false },
                imageLoader = videoImageLoader,
                isCustomTab = pageIdx == 3
            )
        }
    }
}

@Composable
fun OptimalReflowGrid(
    items: List<GalleryMedia>, 
    displayColumns: Int, 
    itemScales: MutableMap<String, Float>, 
    isAutoScroll: Boolean, 
    onManualInteraction: () -> Unit, 
    imageLoader: ImageLoader,
    isCustomTab: Boolean
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollDirection by remember { mutableIntStateOf(1) }
    var activeVideoId by remember { mutableStateOf<String?>(null) }
    
    val totalGridCells = 120 

    val itemSpans = remember(items, displayColumns, itemScales.toMap()) {
        if (items.isEmpty()) return@remember IntArray(0)
        
        val spans = IntArray(items.size)
        var currentLineSpan = 0
        val baseSpan = totalGridCells / displayColumns
        
        for (i in items.indices) {
            val scale = itemScales[items[i].id] ?: 1f
            var preferredSpan = (baseSpan * scale).toInt()
            
            if (items[i].isWide && displayColumns > 1 && scale == 1f) {
                preferredSpan = (baseSpan * 1.5).toInt()
            }
            preferredSpan = preferredSpan.coerceIn(1, totalGridCells)

            val remainingInLine = totalGridCells - currentLineSpan

            if (currentLineSpan > 0 && preferredSpan > remainingInLine) {
                spans[i - 1] += remainingInLine
                currentLineSpan = 0
            }

            spans[i] = preferredSpan
            currentLineSpan += preferredSpan
        }
        
        if (currentLineSpan in 1 until totalGridCells) {
            spans[items.lastIndex] += (totalGridCells - currentLineSpan)
        }
        spans
    }

    LaunchedEffect(isAutoScroll, scrollDirection) {
        if (isAutoScroll) {
            while (isActive) {
                if (scrollDirection == 1 && !gridState.canScrollForward) scrollDirection = -1
                else if (scrollDirection == -1 && !gridState.canScrollBackward) scrollDirection = 1
                gridState.scrollBy(3f * scrollDirection)
                delay(16)
            }
        }
    }

    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) onManualInteraction()
    }

    val centerVideoId by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null

            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2

            visibleItems.asSequence()
                .filter { items.getOrNull(it.index)?.isVideo == true }
                .minByOrNull { info ->
                    val itemCenter = info.offset.y + (info.size.height / 2)
                    abs(viewportCenter - itemCenter) 
                }?.let { info ->
                    items.getOrNull(info.index)?.id
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(totalGridCells),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
                span = { index, _ -> 
                    val safeSpan = if (index < itemSpans.size) itemSpans[index] else (totalGridCells / displayColumns)
                    GridItemSpan(maxOf(1, safeSpan.coerceAtMost(totalGridCells)))
                }
            ) { _, item ->
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                val currentScale = itemScales[item.id] ?: 1f
                
                val baseRowHeight = (360 / displayColumns).dp
                val itemHeight = baseRowHeight * currentScale
                
                Box(Modifier.height(itemHeight).fillMaxWidth()) {
                    DynamicRatioMediaCard(
                        item = item,
                        isPlaying = isPlaying,
                        layoutScale = currentScale,
                        displayColumns = displayColumns,
                        onScaleChange = { newScale -> itemScales[item.id] = newScale },
                        imageLoader = imageLoader,
                        onPlayToggle = { activeVideoId = if (activeVideoId == item.id) null else item.id }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = gridState.firstVisibleItemIndex > 0) {
                FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(0) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignTop, "위로") 
                }
            }
            AnimatedVisibility(visible = gridState.canScrollForward) {
                FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(items.size - 1) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignBottom, "아래로") 
                }
            }
        }

        if (isCustomTab && activeVideoId != null) {
            val scale = itemScales[activeVideoId] ?: 1f
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "동영상 정밀 크기 조절: ${String.format("%.1f", scale)}x", 
                    color = Color.White, 
                    fontSize = 14.sp
                )
                Slider(
                    value = scale,
                    onValueChange = { itemScales[activeVideoId!!] = it },
                    valueRange = 0.5f..4f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DynamicRatioMediaCard(
    item: GalleryMedia, 
    isPlaying: Boolean, 
    layoutScale: Float,
    displayColumns: Int,
    onScaleChange: (Float) -> Unit, 
    imageLoader: ImageLoader, 
    onPlayToggle: () -> Unit
) {
    val context = LocalContext.current
    var isZooming by remember { mutableStateOf(false) }
    var visualScale by remember { mutableFloatStateOf(layoutScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    // 비디오 음소거 기본값 (true)
    var isMuted by remember { mutableStateOf(true) }

    LaunchedEffect(layoutScale) {
        if (!isZooming) {
            visualScale = layoutScale
            offset = Offset.Zero
        }
    }

    val isFullLine = layoutScale > 1.2f || (item.isWide && displayColumns > 1)
    val widthMultiplier = if (isFullLine && displayColumns > 1) displayColumns.toFloat() else 1f
    val targetRatio = ((item.ratio * widthMultiplier) / layoutScale).coerceIn(0.2f, 5f)

    Card(
        modifier = Modifier
            .fillMaxSize() 
            .zIndex(if (isZooming) 1f else 0f)
            .pointerInput(Unit) {
                detectTwoFingerGesture(
                    onGestureStart = { 
                        isZooming = true
                    },
                    onGesture = { pan, zoom ->
                        visualScale = (visualScale * zoom).coerceIn(0.5f, 4f)
                        if (visualScale > 1f) offset += pan else offset = Offset.Zero
                    },
                    onGestureEnd = {
                        isZooming = false
                        onScaleChange(visualScale)
                        offset = Offset.Zero
                    }
                )
            },
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        val renderScale = visualScale / layoutScale

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = renderScale,
                    scaleY = renderScale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).memoryCachePolicy(CachePolicy.ENABLED).crossfade(200).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop, 
                modifier = Modifier.fillMaxSize()
            )
            
            if (item.isVideo) {
                if (isPlaying) {
                    VideoPlayerCore(item.uri, isMuted)
                } else {
                    IconButton(onClick = onPlayToggle) { 
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp)) 
                    }
                }
                
                // [요구사항 1] 더 작고 앙증맞아진 음소거 아이콘
                IconButton(
                    onClick = { isMuted = !isMuted },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, 
                        contentDescription = if (isMuted) "음소거 해제" else "음소거", 
                        tint = Color.White.copy(0.9f), 
                        modifier = Modifier.size(18.dp) // 아이콘 크기 축소
                    )
                }
            }
            
            // [요구사항 2] 배경색(Surface)이 제거되고 텍스트 그림자가 추가된 해상도 텍스트
            Box(
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
            ) {
                Text(
                    text = item.resolutionText, 
                    color = Color.White, 
                    fontSize = 10.sp, 
                    style = TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            offset = Offset(1f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
    }
}

suspend fun PointerInputScope.detectTwoFingerGesture(
    onGestureStart: () -> Unit,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var hasStartedTwoFinger = false

        do {
            val event = awaitPointerEvent()
            val pointers = event.changes.filter { it.pressed }

            if (pointers.size >= 2) {
                if (!hasStartedTwoFinger) {
                    hasStartedTwoFinger = true
                    onGestureStart()
                }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                onGesture(pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pointers.isEmpty()) {
                break
            }
        } while (true)

        if (hasStartedTwoFinger) {
            onGestureEnd()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerCore(uri: Uri, isMuted: Boolean) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (isMuted) 0f else 1f
            
            // [요구사항 4] 음소거 시 오디오 트랙 자체를 비활성화 (리소스 절약 및 포커스 충돌 방지)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, isMuted)
                .build()
                
            prepare()
            playWhenReady = true
        }
    }

    // 재생 중 음소거 상태가 변하면 볼륨 및 트랙 활성화 여부 즉시 업데이트
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, isMuted)
            .build()
    }

    DisposableEffect(uri) { onDispose { exoPlayer.release() } }
    
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
