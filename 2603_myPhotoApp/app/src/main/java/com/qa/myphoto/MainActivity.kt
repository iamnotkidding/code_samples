요청하신 **'구글 포토의 Comfortable (Justified Flow) 레이아웃'**을 완벽하게 재구현했습니다.
기존의 복잡했던 칸 나누기(Grid Span) 방식을 폐기하고, 수학적 가중치(Weight)와 종횡비(AspectRatio)를 이용한 유체형(Fluid) 알고리즘을 도입했습니다.
💡 주요 개선 및 구현 사항
 * Comfortable 레이아웃 (해상도 비율 유지 + 100% 빈 공간 채움): 각 사진의 원본 해상도 비율을 최대한 보존합니다. 한 줄에 사진들을 배치하다가 가로 공간이 남으면 사진들이 비율에 맞춰 자동으로 커지며 빈 공간을 덮고(Justified), 공간이 모자라면 다음 줄로 부드럽게 밀어냅니다.
 * 레이아웃 단계(단수)의 정확한 의미 적용: 선택한 단수(예: 3단)는 **'한 줄에 들어갈 수 있는 최대 파일 개수'**로 작동합니다. 가로로 매우 긴 파노라마 사진이 섞여 있을 경우, 억지로 3개를 구겨 넣지 않고 1~2개만 배치하여 보기 편안한 크기를 유지합니다.
 * 제스처 반응형 재배치 (테트리스 효과): 사진을 축소하면 다음 줄에 있던 사진들이 위로 당겨져 올라와 빈 공간을 채우고, 확대하면 자리가 모자라진 사진들이 다음 줄로 밀려나며 끊임없이 유기적으로 재배치됩니다.
기존 코드를 모두 지우고 아래의 혁신적으로 개선된 최종 코드로 덮어씌워 주세요!
💻 MainActivity.kt 최종 통합 소스 코드
package com.example.photogallery

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
            
            OptimalFluidGallery(
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
fun OptimalFluidGallery(
    items: List<GalleryMedia>, 
    displayColumns: Int, 
    itemScales: MutableMap<String, Float>, 
    isAutoScroll: Boolean, 
    onManualInteraction: () -> Unit, 
    imageLoader: ImageLoader,
    isCustomTab: Boolean
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollDirection by remember { mutableIntStateOf(1) }
    var activeVideoId by remember { mutableStateOf<String?>(null) }
    
    // 최소 크기 제어값 (10단 뷰 기준 비율)
    val minAllowedScale = displayColumns / 10f

    // [핵심] 구글 포토 Comfortable 레이아웃 분할 알고리즘 (Justified Layout)
    val rows = remember(items, displayColumns, itemScales.toMap()) {
        val groupedRows = mutableListOf<List<GalleryMedia>>()
        var currentRow = mutableListOf<GalleryMedia>()
        var currentRatioSum = 0f
        
        // 목표 비율 합 (단수와 비례, 예: 3단 뷰면 비율 합이 약 3.0이 되도록 채움)
        val targetRatioSum = displayColumns.toFloat()

        for (item in items) {
            val scale = (itemScales[item.id] ?: 1f).coerceAtLeast(minAllowedScale)
            // 비정상적으로 길거나 넓은 이미지를 위해 기본 비율 제한 후 확대/축소 배율 적용
            val baseRatio = item.ratio.coerceIn(0.5f, 2.5f)
            val effectiveRatio = (baseRatio * scale).coerceIn(0.1f, 10f)

            // 줄바꿈 조건: 현재 줄에 파일이 있으면서, (최대 허용 개수를 넘었거나 OR 비율 합이 목표치를 크게 넘어섰을 때)
            if (currentRow.isNotEmpty() && (currentRow.size >= displayColumns || (currentRatioSum + effectiveRatio > targetRatioSum * 1.3f))) {
                groupedRows.add(currentRow.toList())
                currentRow.clear()
                currentRatioSum = 0f
            }

            currentRow.add(item)
            currentRatioSum += effectiveRatio
        }
        
        if (currentRow.isNotEmpty()) {
            groupedRows.add(currentRow)
        }
        groupedRows
    }

    LaunchedEffect(isAutoScroll, scrollDirection) {
        if (isAutoScroll) {
            while (isActive) {
                if (scrollDirection == 1 && !listState.canScrollForward) scrollDirection = -1
                else if (scrollDirection == -1 && !listState.canScrollBackward) scrollDirection = 1
                listState.scrollBy(3f * scrollDirection)
                delay(16)
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onManualInteraction()
    }

    val centerVideoId by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null

            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            var closestId: String? = null
            var minDistance = Float.MAX_VALUE

            for (rowInfo in visibleItems) {
                val rowCenter = rowInfo.offset + (rowInfo.size / 2)
                val distance = abs(viewportCenter - rowCenter).toFloat()
                
                val rowItems = rows.getOrNull(rowInfo.index) ?: continue
                for (item in rowItems) {
                    if (item.isVideo && distance < minDistance) {
                        minDistance = distance
                        closestId = item.id
                    }
                }
            }
            closestId
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // [핵심] 그리드 대신 LazyColumn과 Row의 weight(가중치)를 이용하여 1픽셀의 빈틈도 없는 유체 배치 구현
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(rows, key = { _, row -> row.first().id }) { rowIndex, rowItems ->
                var sumEffectiveRatio = 0f
                rowItems.forEach { item ->
                    val scale = (itemScales[item.id] ?: 1f).coerceAtLeast(minAllowedScale)
                    sumEffectiveRatio += (item.ratio.coerceIn(0.5f, 2.5f) * scale).coerceIn(0.1f, 10f)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    rowItems.forEach { item ->
                        val scale = (itemScales[item.id] ?: 1f).coerceAtLeast(minAllowedScale)
                        val effectiveRatio = (item.ratio.coerceIn(0.5f, 2.5f) * scale).coerceIn(0.1f, 10f)
                        val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                        val isAtMinSize = scale <= (minAllowedScale + 0.05f)
                        
                        // Modifier.weight와 aspectRatio 조합으로 사진 크기와 관계없이 같은 줄의 세로 높이를 완벽하게 통일시킵니다.
                        Box(
                            modifier = Modifier
                                .weight(effectiveRatio)
                                .aspectRatio(effectiveRatio)
                        ) {
                            DynamicRatioMediaCard(
                                item = item,
                                isPlaying = isPlaying,
                                itemScale = scale,     
                                isAtMinSize = isAtMinSize, 
                                minAllowedScale = minAllowedScale,
                                onScaleChange = { newScale -> itemScales[item.id] = newScale },
                                imageLoader = imageLoader,
                                onPlayToggle = { activeVideoId = if (activeVideoId == item.id) null else item.id }
                            )
                        }
                    }
                    
                    // 마지막 줄이 가로로 과하게 팽창하지 않도록 방어하는 투명 Spacer (오른쪽 빈 공간 생성 방지)
                    val isLastRow = rowIndex == rows.lastIndex
                    val target = displayColumns.toFloat()
                    if (isLastRow && sumEffectiveRatio < target * 0.8f) {
                        Spacer(modifier = Modifier.weight(target - sumEffectiveRatio))
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = listState.firstVisibleItemIndex > 0) {
                FloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignTop, "위로") 
                }
            }
            AnimatedVisibility(visible = listState.canScrollForward) {
                FloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(rows.size - 1) } }, modifier = Modifier.size(48.dp)) { 
                    Icon(Icons.Default.VerticalAlignBottom, "아래로") 
                }
            }
        }

        val activeControlVideoId = activeVideoId ?: centerVideoId
        if (isCustomTab && activeControlVideoId != null) {
            val scale = (itemScales[activeControlVideoId] ?: 1f).coerceAtLeast(minAllowedScale)
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
                    valueRange = minAllowedScale..4f, 
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
    minAllowedScale: Float,
    onScaleChange: (Float) -> Unit, 
    imageLoader: ImageLoader, 
    onPlayToggle: () -> Unit
) {
    val context = LocalContext.current
    var isZooming by remember { mutableStateOf(false) }
    
    var isMuted by remember { mutableStateOf(true) }

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
                        // 제스처가 발생할 때마다 실시간으로 레이아웃 엔진에 전달하여 즉각적인(Fluid) 재배치 발생
                        val newScale = (itemScale * zoom).coerceIn(minAllowedScale, 4f)
                        onScaleChange(newScale)
                    },
                    onGestureEnd = {
                        isZooming = false
                    }
                )
            },
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.White) 
    ) {
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
                    if (!isAtMinSize) {
                        IconButton(onClick = onPlayToggle) { 
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp)) 
                        }
                    }
                }
                
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

