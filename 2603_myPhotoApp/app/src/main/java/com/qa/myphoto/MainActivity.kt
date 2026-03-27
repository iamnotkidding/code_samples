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

// 미디어 데이터 클래스 (비율 정보 추가)
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

// [권한] Android 14+ 대응 미디어 권한 체크
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
    
    // 레이아웃 단수(열 개수) 상태
    var columnCount by remember { mutableIntStateOf(3) }
    var isAutoScrollEnabled by remember { mutableStateOf(false) }
    val totalMedia = remember { mutableStateListOf<GalleryMedia>() }
    
    // [해결 1, 3] 각 사진의 가변 스케일 저장 맵
    val itemScales = remember { mutableStateMapOf<String, Float>() }

    // 미디어 로딩 (데이트 추가순 정렬)
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
                        
                        // 구글 포토 스타일 Comfortable 배치를 위해 원본 비율 저장
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
                // 상단 탭 (스와이프 연동)
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                // 레이아웃 컨트롤 패널
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    // [해결 2] 레이아웃 초기화 버튼 추가
                    IconButton(onClick = { 
                        itemScales.clear() // 모든 줌 상태 리셋
                        columnCount = 3    // 열 개수 기본값 리셋
                    }) { 
                        Icon(Icons.Default.Refresh, "레이아웃 초기화") 
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    IconButton(onClick = { if (columnCount < 5) columnCount++ }) { Icon(Icons.Default.Remove, "작게") }
                    Text("${columnCount}단 뷰", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    IconButton(onClick = { if (columnCount > 1) columnCount-- }) { Icon(Icons.Default.Add, "크게") }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Button(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }) {
                        Text(if (isAutoScrollEnabled) "정지" else "자동 스크롤")
                    }
                }
            }
        }
    ) { padding ->
        // [제스처] 1손가락 좌우 스와이프 탭 이동
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
            
            OptimalReflowGrid(
                items = filtered,
                displayColumns = columnCount,
                itemScales = itemScales, // 스케일 상태 전달
                isAutoScroll = isAutoScrollEnabled,
                onManualInteraction = { isAutoScrollEnabled = false },
                imageLoader = videoImageLoader
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
    imageLoader: ImageLoader
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

    // [제스처] 자동 재생 로직 (화면 중앙에 가장 가까운 비디오 감지)
    val centerVideoId by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null

            // 현재 화면(뷰포트)의 정중앙 Y좌표 계산
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2

            visibleItems.asSequence()
                .filter { items.getOrNull(it.index)?.isVideo == true }
                .minByOrNull { info ->
                    val itemCenter = info.offset.y + (info.size.height / 2)
                    abs(viewportCenter - itemCenter) // 중앙과의 거리 계산
                }?.let { info ->
                    items.getOrNull(info.index)?.id
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // [레이아웃] Comfortable 배치를 위한 LazyVerticalStaggeredGrid 사용
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(displayColumns),
            modifier = Modifier.fillMaxSize(),
            // 갭리스 배치를 위한 간격 0dp 설정
            contentPadding = PaddingValues(0.dp),
            verticalItemSpacing = 0.dp,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(
                items = items,
                key = { it.id },
                span = { item ->
                    val scale = itemScales[item.id] ?: 1f
                    // [해결 1, 3] 줌 인/아웃 상태에 따라 레이아웃 가로폭(Span) 결정
                    if (scale > 1.2f || (item.isWide && displayColumns > 1)) {
                        StaggeredGridItemSpan.FullLine // 확대되었거나 와이드면 가로 전체 차지
                    } else if (scale < 0.8f && displayColumns > 1) {
                        StaggeredGridItemSpan.SingleLane // 많이 축소되면 한 칸만 차지
                    } else {
                        StaggeredGridItemSpan.SingleLane // 기본
                    }
                }
            ) { item ->
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                val currentScale = itemScales[item.id] ?: 1f
                
                // [해결 1, 3] 핵심: graphicsLayer(scale) 대신 줌 스케일에 맞게 AspectRatio(비율) 자체를 변경
                // 이렇게 해야 확대 시 세로 크기가 늘어나며 아래 사진들을 밀어내고(재배치), 축소 시 세로가 줄어들며 사진들이 밀착됨
                DynamicRatioMediaCard(
                    item = item,
                    isPlaying = isPlaying,
                    scale = currentScale,
                    onScaleChange = { newScale -> itemScales[item.id] = newScale },
                    imageLoader = imageLoader,
                    onPlayToggle = { activeVideoId = if (activeVideoId == item.id) null else item.id }
                )
            }
        }

        // 최상/하단 퀵 이동 버튼
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
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DynamicRatioMediaCard(
    item: GalleryMedia, 
    isPlaying: Boolean, 
    scale: Float, 
    onScaleChange: (Float) -> Unit, 
    imageLoader: ImageLoader, 
    onPlayToggle: () -> Unit
) {
    val context = LocalContext.current
    var isZooming by remember { mutableStateOf(false) }
    // 제스처 중 실시간으로 보여줄 시각적 스케일
    var visualScale by remember { mutableFloatStateOf(scale) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 부모 레이아웃의 확정 스케일이 외부에서 변경되면 시각 스케일 동기화
    LaunchedEffect(scale) {
        if (!isZooming) visualScale = scale
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 줌 중에는 맨 위로 띄움
            .zIndex(if (isZooming) 1f else 0f)
            // [해결 1, 3] 핵심: 줌 수치(layoutScale)를 반영하여 AspectRatio(비율) 재계산
            // 확대하면 비율이 작아져 카드가 길어지고, 축소하면 비율이 커져 카드가 납작해지며 레이아웃이 유동적으로 재배치됨
            .aspectRatio((item.ratio / scale).coerceIn(0.2f, 5f))
            .pointerInput(Unit) {
                // [제스처] 1손가락 스크롤과 충돌을 막기 위해 2손가락일 때만 시작/종료 이벤트를 발생
                detectTwoFingerGesture(
                    onGestureStart = { isZooming = true },
                    onGesture = { pan, zoom ->
                        // 줌 인(최대 4배) 및 줌 아웃(최소 0.5배) 허용
                        visualScale = (visualScale * zoom).coerceIn(0.5f, 4f)
                        if (visualScale > 1f) offset += pan else offset = Offset.Zero
                    },
                    onGestureEnd = {
                        isZooming = false
                        // 손을 떼는 순간, 시각적 스케일을 레이아웃 스케일로 확정하여 그리드 전체 재배치 트리거
                        onLayoutScaleChange(visualScale)
                        offset = Offset.Zero
                    }
                )
            },
        // 갭리스 배치를 위해 직각 처리
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        // 실제 화면에 렌더링되는 크기는 (현재 시각적 줌 / 확정된 레이아웃 줌)
        // -> 제스처가 끝나면 scale == visualScale이 되므로 배율이 1.0으로 부드럽게 초기화 됨
        val renderScale = visualScale / scale

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
            // ContentScale.Crop으로 할당된 공간(비율)을 흰색 여백 없이 가득 채움
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
                    Icon(Icons.Default.VideoCameraBack, null, tint = Color.White.copy(0.7f), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                    IconButton(onClick = onPlayToggle) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                }
            }
            
            // 줌 아닐 때만 해상도 정보 표시
            if (!isZooming && scale == 1f) {
                Surface(color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                    Text(text = item.resolutionText, color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

// 스크롤과 충돌을 막기 위해 2손가락일 때만 동작하는 커스텀 제스처 디텍터
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
                // 이벤트를 소비하여 상하 스크롤 차단
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
