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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
                    
                    // 레이아웃 레벨 최대 10단까지 열어둠
                    IconButton(onClick = { if (columnCount < 10) columnCount++ }) { Icon(Icons.Default.Remove, "작게") }
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
    
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val screenHeightDp = configuration.screenHeightDp.toFloat()

    // [핵심] 유동적 줄바꿈(Flow Wrap) 및 빈 공간 테트리스 100% 채우기 알고리즘
    val (itemSpans, itemHeights, isMinSizeFlags) = remember(items, displayColumns, itemScales.toMap(), screenWidthDp, screenHeightDp) {
        if (items.isEmpty()) return@remember Triple(IntArray(0), FloatArray(0), BooleanArray(0))

        val TOTAL_SPANS = 2520 // 모든 정수비율을 소화하기 위한 공배수 
        val spans = IntArray(items.size)
        val heights = FloatArray(items.size)
        val minSizeFlags = BooleanArray(items.size)

        // [요구사항 1] 전체 디바이스 화면 가로/세로 중 가장 큰 값의 1/5을 절대 최소 한계선(dp)으로 설정
        val minDimensionDp = maxOf(screenWidthDp, screenHeightDp) / 5f
        
        // 논리적 계산을 위한 단위 환산
        val minLogicalWidthUnit = (minDimensionDp / screenWidthDp) * displayColumns

        var i = 0
        while (i < items.size) {
            val rowIndices = mutableListOf<Int>()
            var currentRowLogicalWidth = 0f

            var j = i
            while (j < items.size) {
                val item = items[j]
                val scale = itemScales[item.id] ?: 1f

                // 유저가 설정한 고유 비율과 줌 스케일
                val originalLw = item.ratio * scale
                // [요구사항 1] 절대 최소 한계선에 부딪히면 그 밑으로는 내려가지 못하게 강제 방어
                val requiredMinLw = minLogicalWidthUnit * maxOf(1f, item.ratio)
                val lw = maxOf(originalLw, requiredMinLw)

                // [요구사항 3] 이번 파일을 넣었을 때 줄 가로 한계치(displayColumns)를 넘는다면 줄바꿈(Wrap) 발생
                if (rowIndices.isNotEmpty() && (currentRowLogicalWidth + lw) > displayColumns) {
                    break
                }

                rowIndices.add(j)
                currentRowLogicalWidth += lw
                
                // [요구사항 3] 파일 크기가 절대 최소 크기에 도달했는지 판별(아이콘 숨김용 플래그)
                minSizeFlags[j] = originalLw <= requiredMinLw + 0.01f

                // 한 파일 자체가 너무 거대해서 이미 줄을 넘어선 경우 강제 줄바꿈
                if (currentRowLogicalWidth >= displayColumns) {
                    j++
                    break
                }
                j++
            }

            // --- 줄 묶음(Row) 확정 후 오른쪽/하단 100% 빈틈 채우기 연산 ---
            val sumLw = rowIndices.sumOf { k ->
                val scale = itemScales[items[k].id] ?: 1f
                val requiredMinLw = minLogicalWidthUnit * maxOf(1f, items[k].ratio)
                maxOf(items[k].ratio * scale, requiredMinLw).toDouble()
            }.toFloat()

            var allocatedSpans = 0
            var maxRowHeightDp = 0f

            for (idx in rowIndices.indices) {
                val k = rowIndices[idx]
                val scale = itemScales[items[k].id] ?: 1f
                val requiredMinLw = minLogicalWidthUnit * maxOf(1f, items[k].ratio)
                val lw = maxOf(items[k].ratio * scale, requiredMinLw)

                // [요구사항 2] 가로 오른쪽 끝 빈공간을 강제로 채우기 위해 마지막 파일이 남은 Spans 전부 흡수
                val span = if (idx == rowIndices.lastIndex) {
                    TOTAL_SPANS - allocatedSpans
                } else {
                    (TOTAL_SPANS * (lw / sumLw)).toInt()
                }
                spans[k] = span
                allocatedSpans += span

                // 할당된 가로폭(Span) 기반으로 비율을 지키기 위한 최적의 세로 높이 계산
                val itemWidthDp = screenWidthDp * (span.toFloat() / TOTAL_SPANS)
                val itemHeightDp = itemWidthDp / items[k].ratio

                if (itemHeightDp > maxRowHeightDp) {
                    maxRowHeightDp = itemHeightDp
                }
            }

            // [요구사항 2] 세로 하단 빈공간을 강제로 덮기 위해 같은 줄의 모든 파일 높이를 Max로 통일 (Crop 됨)
            for (k in rowIndices) {
                heights[k] = maxRowHeightDp
            }
            i = j
        }

        Triple(spans, heights, minSizeFlags)
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

    // 파일 경계색 배경 지정 (White)
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
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
            ) { index, item ->
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                val itemScale = itemScales[item.id] ?: 1f
                
                // 물리적 높이가 이미 빈 공간 100% 채움 연산으로 계산되어 넘어옵니다.
                val layoutHeightDp = if (index < itemHeights.size) itemHeights[index] else 200f
                val isAtMinSize = if (index < isMinSizeFlags.size) isMinSizeFlags[index] else false
                
                Box(Modifier.height(layoutHeightDp.dp).fillMaxWidth()) {
                    DynamicRatioMediaCard(
                        item = item,
                        isPlaying = isPlaying,
                        itemScale = itemScale,     
                        isAtMinSize = isAtMinSize, // 최소 크기 도달 여부 주입
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

        // [요구사항 4] 커스텀 조절바는 커스텀 탭 + 선택/재생 중인 영상이 있으면 항상 보이게 유지
        val activeControlVideoId = activeVideoId ?: centerVideoId
        if (isCustomTab && activeControlVideoId != null) {
            val scale = itemScales[activeControlVideoId] ?: 1f
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
                    onValueChange = { itemScales[activeControlVideoId] = it },
                    // 최소치로 쉽게 스와이프 축소할 수 있도록 범위 한계치 0.1f 개방
                    valueRange = 0.1f..4f, 
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
    itemScale: Float,
    isAtMinSize: Boolean,
    onScaleChange: (Float) -> Unit, 
    imageLoader: ImageLoader, 
    onPlayToggle: () -> Unit
) {
    val context = LocalContext.current
    var isZooming by remember { mutableStateOf(false) }
    var visualScale by remember { mutableFloatStateOf(itemScale) }
    
    var isMuted by remember { mutableStateOf(true) }

    // 부모 레이아웃이 확정되면 시각 스케일 일치화
    LaunchedEffect(itemScale) {
        if (!isZooming) {
            visualScale = itemScale
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize() 
            .zIndex(if (isZooming) 1f else 0f)
            .pointerInput(Unit) {
                detectTwoFingerGesture(
                    onGestureStart = { 
                        isZooming = true
                    },
                    onGesture = { _, zoom ->
                        // 제스처가 발생할 때마다 실시간으로 레이아웃 엔진에 크기 변화를 전달하여 실시간 Reflow 발생
                        visualScale = (visualScale * zoom).coerceIn(0.1f, 5f)
                        onScaleChange(visualScale)
                    },
                    onGestureEnd = {
                        isZooming = false
                    }
                )
            },
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.White) 
    ) {
        // 이미 100% 빈틈 채우기를 통해 할당된 부모 영역을 Crop 하여 그대로 채웁니다.
        Box(
            modifier = Modifier.fillMaxSize(),
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
                    // [요구사항 3] 최소 크기 한계선에 도달한 아이템은 재생 버튼 숨김
                    if (!isAtMinSize) {
                        IconButton(onClick = onPlayToggle) { 
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp)) 
                        }
                    }
                }
                
                // [요구사항 3] 최소 크기 도달 시 음소거 버튼 숨김
                if (!isAtMinSize) {
                    IconButton(
                        onClick = { isMuted = !isMuted },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, 
                            contentDescription = if (isMuted) "음소거 해제" else "음소거", 
                            tint = Color.White.copy(0.9f), 
                            modifier = Modifier.size(18.dp) 
                        )
                    }
                }
            }
            
            // [요구사항 3] 최소 크기 도달 시 해상도 텍스트 숨김
            if (!isAtMinSize) {
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
            
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, isMuted)
                .build()
                
            prepare()
            playWhenReady = true
        }
    }

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
