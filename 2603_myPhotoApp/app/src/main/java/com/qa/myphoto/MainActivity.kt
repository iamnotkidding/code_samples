package com.qa.myphoto


import android.Manifest
import android.content.ContentUris
import android.net.Uri
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
                PermissionCheck {
                    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        MainGalleryApp()
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCheck(content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { g -> g }
    }
    LaunchedEffect(Unit) {
        launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
    }
    if (granted) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainGalleryApp() {
    val context = LocalContext.current
    val tabs = listOf("전체", "사진", "동영상")
    val videoImageLoader = remember { ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    
    // [요구사항 7] 레이아웃 줌 레벨 (버튼으로만 조절)
    var columnCount by remember { mutableIntStateOf(3) }
    val totalMedia = remember { mutableStateListOf<GalleryMedia>() }

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val localItems = mutableListOf<GalleryMedia>()
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val w = cursor.getInt(wCol).coerceAtLeast(1)
                    val h = cursor.getInt(hCol).coerceAtLeast(1)
                    val isVideo = (cursor.getString(mimeCol) ?: "").startsWith("video")
                    val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    
                    // 가로가 긴 사진 판별
                    localItems.add(GalleryMedia("local_$id", uri, isVideo, "${w}x${h}", w.toFloat() / h > 1.3f))
                }
            }
            withContext(Dispatchers.Main) { totalMedia.addAll(localItems) }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                // [요구사항 4] 탭 UI
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                // [요구사항 7] 별도의 버튼으로 레이아웃 줌 레벨 컨트롤
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (columnCount < 5) columnCount++ }) { Icon(Icons.Default.Remove, "작게 보기") }
                    Text("레이아웃 ${columnCount}단", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    IconButton(onClick = { if (columnCount > 1) columnCount-- }) { Icon(Icons.Default.Add, "크게 보기") }
                }
            }
        }
    ) { padding ->
        // [요구사항 4] 한 손가락 좌우 스와이프 탭 이동
        HorizontalPager(
            state = pagerState, 
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { pageIdx ->
            val filtered = when (pageIdx) {
                1 -> totalMedia.filter { !it.isVideo }
                2 -> totalMedia.filter { it.isVideo }
                else -> totalMedia
            }
            
            StrictGaplessRowGrid(
                items = filtered,
                displayColumns = columnCount,
                imageLoader = videoImageLoader
            )
        }
    }
}

@Composable
fun StrictGaplessRowGrid(items: List<GalleryMedia>, displayColumns: Int, imageLoader: ImageLoader) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var activeVideoId by remember { mutableStateOf<String?>(null) }
    
    // 그리드 세분화: 60칸으로 쪼개서 비율을 유동적으로 분배 (공배수 활용)
    val totalGridCells = 60 

    // 화면 중앙에 위치한 영상을 감지하여 자동 재생
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
        // [요구사항 1, 5] 한 손가락 상하 스크롤 처리 및 그리드 배치
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(totalGridCells),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                // [요구사항 2] 공간이 남으면 강제로 비율을 조정하여 남은 공간에 맞춤
                val baseSpan = totalGridCells / displayColumns
                val preferredSpan = if (item.isWide) (baseSpan * 1.5).toInt().coerceAtMost(totalGridCells) else baseSpan
                
                // 현재 줄(Row)의 남은 칸수(maxCurrentLineSpan)보다 선호하는 크기가 크거나,
                // 리스트의 가장 마지막 아이템일 경우 무조건 남은 칸을 100% 채워서 빈 공간을 소멸시킴
                val isLastItem = index == items.lastIndex
                val actualSpan = if (maxCurrentLineSpan < preferredSpan || isLastItem) maxCurrentLineSpan else preferredSpan
                
                val isPlaying = item.id == activeVideoId || (activeVideoId == null && item.id == centerVideoId)
                
                // 행의 높이를 열 개수에 반비례하도록 일정하게 고정하여 조화로운 타일(Masonry) 느낌 제공
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

        // [요구사항 6] 상하 끝으로 한 번에 이동하는 퀵 버튼
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
            // 줌 인 시 다른 아이템 위에 그려지도록 Z축 우선순위 부여
            .zIndex(if (scale > 1f) 1f else 0f)
            .pointerInput(Unit) {
                // [요구사항 3] 두 손가락 터치로만 동작하는 줌 인/아웃 및 이동 제스처
                detectTwoFingerGesture { pan, zoom ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) offset += pan else offset = Offset.Zero
                }
            },
        shape = RectangleShape,
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
            // [요구사항 2] ContentScale.Crop으로 할당된 공간(찌그러지거나 늘어난 비율)을 흰색 여백 없이 가득 채움
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
            
            // 확대되지 않았을 때만 해상도 정보 표시
            if (scale == 1f) {
                Surface(color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                    Text(text = item.resolutionText, color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

// [요구사항 3, 5 충돌 방지] 오직 두 개 이상의 손가락이 터치되었을 때만 줌/팬 이벤트를 처리하는 커스텀 디텍터
suspend fun PointerInputScope.detectTwoFingerGesture(onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pointers = event.changes.filter { it.pressed }

            if (pointers.size >= 2) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                onGesture(pan, zoom)
                // 이벤트를 소비하여 상하 스크롤 뷰가 움직이지 않도록 차단
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (pointers.isNotEmpty())
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
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // 비디오 역시 빈 공간 없이 꽉 채움
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
