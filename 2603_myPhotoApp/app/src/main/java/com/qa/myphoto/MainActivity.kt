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
import androidx.compose.foundation.lazy.staggeredgrid.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
    // [요구사항 5] 커스텀 탭 추가
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
                    // [요구사항 2] 리셋 버튼: 스케일만 초기화하고, 현재 레벨(단수)은 유지
                    IconButton(onClick = { 
                        itemScales.clear() 
                    }) { 
                        Icon(Icons.Default.Refresh, "재배치 초기화") 
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(onClick = { if (columnCount < 5) columnCount++ }) { Icon(Icons.Default.Remove, "작게") }
                    Text("${columnCount}단 뷰", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { if (columnCount > 1) columnCount-- }) { Icon(Icons.Default.Add, "크게") }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // [요구사항 1] 버튼 문자열 자동 반영 (isAutoScrollEnabled 상태에 따라 즉각 변경)
                    Button(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }) {
                        Text(if (isAutoScrollEnabled) "자동 스크롤 중지" else "자동 스크롤 시작")
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
                    3 -> totalMedia.filter { it.isVideo } // 커스텀 탭도 비디오 필터 적용
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
                isCustomTab = pageIdx == 3 // 커스텀 탭 여부 전달
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
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    var scrollDirection by remember { mutableIntStateOf(1) }
    var activeVideoId by remember { mutableStateOf<String?>(null) }
    
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
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(displayColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            verticalItemSpacing = 0.dp,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(
                items = items,
                key = { it.id },
                span = { item ->
                    val scale = itemScales[item.id] ?: 1f
                    if (scale > 1.2f || (item.isWide && displayColumns > 1) || isCustomTab && activeVideoId == item.id) {
                        StaggeredGridItemSpan.FullLine 
                    } else {
                        StaggeredGridItemSpan.SingleLane 
                    }
                }
            ) { item ->
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                val currentScale = itemScales[item.id] ?: 1f
                
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

        // 퀵 이동 버튼
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = gridState.firstVisibleItemIndex > 0) {
                FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(0) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignTop, "맨 위로") 
                }
            }
            AnimatedVisibility(visible = gridState.canScrollForward) {
                FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(items.size - 1) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignBottom, "맨 아래로") 
                }
            }
        }

        // [요구사항 5] 커스텀 탭 전용: 동영상 선택 시 하단에 정밀 스케일 슬라이더 패널 고정
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
    
    // [요구사항 3] 개별 카드 정밀 줌 슬라이더 토글 상태
    var showInCardSlider by remember { mutableStateOf(false) }

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
            .fillMaxWidth()
            .zIndex(if (isZooming || showInCardSlider) 1f else 0f)
            .aspectRatio(targetRatio)
            .pointerInput(Unit) {
                detectTwoFingerGesture(
                    onGestureStart = { 
                        isZooming = true
                        showInCardSlider = false // 제스처 시작 시 슬라이더 숨김
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
                    VideoPlayerCore(item.uri)
                } else {
                    IconButton(onClick = onPlayToggle) { 
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp)) 
                    }
                }
                // [요구사항 4] 동영상 여부 아이콘 항상 표시
                Icon(
                    Icons.Default.VideoCameraBack, 
                    contentDescription = null, 
                    tint = Color.White.copy(0.9f), 
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                )
            }
            
            // [요구사항 4] 해상도 정보 항상 표시 (isZooming 무관하게)
            Surface(
                color = Color.Black.copy(alpha = 0.5f), 
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
            ) {
                Text(
                    text = item.resolutionText, 
                    color = Color.White, 
                    fontSize = 8.sp, 
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // [요구사항 3] 개별 정밀 컨트롤을 위한 튠(Tune) 버튼
            IconButton(
                onClick = { showInCardSlider = !showInCardSlider },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(Icons.Default.Tune, "정밀 조절", tint = Color.White.copy(alpha = 0.8f))
            }

            // 튠 버튼을 눌렀을 때 나타나는 개별 정밀 조절 슬라이더
            if (showInCardSlider) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 20.dp) // 해상도 텍스트와 겹치지 않게 띄움
                ) {
                    Slider(
                        value = visualScale,
                        onValueChange = { 
                            visualScale = it 
                            onScaleChange(it) // 드래그 할 때마다 실시간으로 레이아웃에 반영
                        },
                        valueRange = 0.5f..4f
                    )
                }
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
fun VideoPlayerCore(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
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
