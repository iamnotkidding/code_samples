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

data class GalleryMedia(
    val id: String,
    val uri: Uri,
    val isVideo: Boolean,
    val resolutionText: String,
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
        // [API 34 대응] 전체 권한 또는 부분 권한(사용자 선택) 중 하나라도 허용되면 승인으로 간주
        val imagesGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val videoGranted = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val visualUserSelectedGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
        } else {
            false
        }
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

        isGranted = (imagesGranted && videoGranted) || visualUserSelectedGranted || storageGranted
    }
    
    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else { // API 32 이하
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
    val tabs = listOf("전체", "사진", "동영상")
    
    // Coil 캐시 및 디코더 설정
    val videoImageLoader = remember { 
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build() 
    }
    
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    
    // [요구사항 7] 줌 레벨 버튼 컨트롤 상태
    var columnCount by remember { mutableIntStateOf(3) }
    var isAutoScrollEnabled by remember { mutableStateOf(false) }
    val totalMedia = remember { mutableStateListOf<GalleryMedia>() }

    // MediaStore 쿼리 (API 34 부분 권한 안전 처리)
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val localItems = mutableListOf<GalleryMedia>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, 
                MediaStore.MediaColumns.MIME_TYPE, 
                MediaStore.MediaColumns.WIDTH, 
                MediaStore.MediaColumns.HEIGHT
            )
            
            try {
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val wCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val hCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        // 0나누기 방지 (API 34부터 width, height가 비어있을 수 있는 엣지 케이스 대응)
                        val w = maxOf(1, cursor.getInt(wCol))
                        val h = maxOf(1, cursor.getInt(hCol))
                        
                        val isVideo = (cursor.getString(mimeCol) ?: "").startsWith("video")
                        val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        
                        // [요구사항 1] 가로 비율이 높으면 와이드 판별
                        localItems.add(GalleryMedia("local_$id", uri, isVideo, "${w}x${h}", w.toFloat() / h > 1.3f))
                    }
                }
                withContext(Dispatchers.Main) { totalMedia.addAll(localItems) }
            } catch (e: SecurityException) {
                // API 34 선택적 권한에서 벗어난 파일 접근 시 크래시 방지
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                // [요구사항 4] 상단 탭 (스와이프 연동)
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                // [요구사항 7] 줌 레벨 전용 컨트롤
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (columnCount < 5) columnCount++ }) { Icon(Icons.Default.Remove, "작게") }
                    Text("레이아웃 ${columnCount}단", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    IconButton(onClick = { if (columnCount > 1) columnCount-- }) { Icon(Icons.Default.Add, "크게") }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }) {
                        Text(if (isAutoScrollEnabled) "정지" else "자동 스크롤")
                    }
                }
            }
        }
    ) { padding ->
        // [요구사항 4] 1손가락 스와이프 탭 이동
        HorizontalPager(
            state = pagerState, 
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { pageIdx ->
            val filtered = remember(pageIdx, totalMedia.size) {
                when (pageIdx) {
                    1 -> totalMedia.filter { !it.isVideo }
                    2 -> totalMedia.filter { it.isVideo }
                    else -> totalMedia
                }
            }
            
            OptimalGaplessGrid(
                items = filtered,
                displayColumns = columnCount,
                isAutoScroll = isAutoScrollEnabled,
                onManualInteraction = { isAutoScrollEnabled = false },
                imageLoader = videoImageLoader
            )
        }
    }
}

@Composable
fun OptimalGaplessGrid(items: List<GalleryMedia>, displayColumns: Int, isAutoScroll: Boolean, onManualInteraction: () -> Unit, imageLoader: ImageLoader) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollDirection by remember { mutableIntStateOf(1) }
    var activeVideoId by remember { mutableStateOf<String?>(null) }
    
    // [요구사항 2] 비율 계산용 공배수 (1,2,3,4,5 단일 대응)
    val totalGridCells = 60 

    // [요구사항 1, 2] 빈 공간을 100% 매워버리는 사전 계산 Span 로직
    val itemSpans = remember(items, displayColumns) {
        val spans = IntArray(items.size)
        var currentLineSpan = 0
        val baseSpan = totalGridCells / displayColumns
        
        for (i in items.indices) {
            val item = items[i]
            val preferredSpan = if (item.isWide && displayColumns > 1) {
                (baseSpan * 1.5).toInt().coerceAtMost(totalGridCells)
            } else {
                baseSpan
            }
            
            val remainingInLine = totalGridCells - currentLineSpan
            val isLastItem = i == items.lastIndex
            
            // 핵심 로직: 남은 공간이 필요한 공간보다 좁거나 리스트 마지막이면, 무조건 남은 칸을 덮어씌움 (Gapless)
            val actualSpan = if (remainingInLine < preferredSpan || isLastItem) remainingInLine else preferredSpan
            
            spans[i] = maxOf(1, actualSpan)
            currentLineSpan = (currentLineSpan + actualSpan) % totalGridCells
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

    // [요구사항 5] 1손가락 스크롤 시 자동 스크롤 기능 정지
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) onManualInteraction()
    }

    val centerVideoId by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null
            visibleItems.firstOrNull { info ->
                val isVideo = items.getOrNull(info.index)?.isVideo == true
                isVideo && info.offset.y >= layoutInfo.viewportStartOffset && (info.offset.y + info.size.height) <= layoutInfo.viewportEndOffset
            }?.key.toString()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // [요구사항 5] 1손가락 터치 레이아웃 상하 스크롤
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(totalGridCells),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
                span = { index, _ -> GridItemSpan(itemSpans[index]) }
            ) { _, item ->
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                val rowHeight = (360 / displayColumns).dp
                
                Box(Modifier.height(rowHeight)) {
                    ZoomableMediaCard(
                        item = item,
                        isPlaying = isPlaying,
                        imageLoader = imageLoader,
                        onPlayToggle = { activeVideoId = if (activeVideoId == item.id) null else item.id }
                    )
                }
            }
        }

        // [요구사항 6] 레이아웃 상하 끝으로 한 번에 이동하는 퀵 버튼
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
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ZoomableMediaCard(
    item: GalleryMedia, 
    isPlaying: Boolean, 
    imageLoader: ImageLoader, 
    onPlayToggle: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (scale > 1f) 1f else 0f) // 확대 시 최상단
            .pointerInput(Unit) {
                // [요구사항 3] 두 손가락 터치로만 동작하는 확대/축소/이동 커스텀 제스처
                detectTwoFingerGesture { pan, zoom ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) offset += pan else offset = Offset.Zero
                }
            },
        shape = RectangleShape, // 둥근 테두리 없이 빈 공간 완벽 제거
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentAlignment = Alignment.Center
        ) {
            // [요구사항 2] Crop을 통해 할당된 Span(비율)에 맞게 꽉 채움
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).memoryCachePolicy(CachePolicy.ENABLED).crossfade(200).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop, 
                modifier = Modifier.fillMaxSize()
            )
            
            // 영상 플레이어 처리
            if (item.isVideo) {
                if (isPlaying) {
                    VideoPlayerCore(item.uri)
                } else {
                    Icon(Icons.Default.VideoCameraBack, null, tint = Color.White.copy(0.7f), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                    IconButton(onClick = onPlayToggle) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                }
            }
            
            // 줌 아닐 때만 텍스트 표시
            if (scale == 1f) {
                Surface(color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                    Text(text = item.resolutionText, color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

// [핵심] 스크롤(1손가락)과 줌(2손가락) 충돌을 방지하는 디텍터
suspend fun PointerInputScope.detectTwoFingerGesture(onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        try {
            do {
                val event = awaitPointerEvent()
                val pointers = event.changes.filter { it.pressed }

                if (pointers.size >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    onGesture(pan, zoom)
                    // 2손가락 시 이벤트 소비 -> 1손가락 스크롤 멈춤 방지
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (pointers.isNotEmpty())
        } catch (e: Exception) {
            // 제스처 캔슬 방어
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
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // 비디오 강제 꽉 채움
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
