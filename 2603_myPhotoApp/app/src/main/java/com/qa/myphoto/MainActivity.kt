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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    val isOnline: Boolean,
    val ratio: Float,
    val resolutionText: String,
    val spanWeight: Int // 1: 일반, 2: 와이드(빈 공간 매움용)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialTabName = intent.getStringExtra("tab_name") ?: "전체"
        val initialAutoScroll = intent.getBooleanExtra("auto_scroll", false)
        val initialZoomLevel = intent.getIntExtra("zoom_level", 3).coerceIn(1, 5)

        setContent {
            MaterialTheme {
                PermissionCheck {
                    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        MainGalleryApp(initialTabName, initialAutoScroll, initialZoomLevel)
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
fun MainGalleryApp(initialTab: String, initialAutoScroll: Boolean, initialZoom: Int) {
    val context = LocalContext.current
    val tabs = listOf("전체", "사진", "동영상", "단말", "온라인")
    val videoImageLoader = remember { ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build() }
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(initialTab).coerceAtLeast(0), pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    var isAutoScrollEnabled by remember { mutableStateOf(initialAutoScroll) }
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
                    val ratio = w.toFloat() / h
                    val isVideo = (cursor.getString(mimeCol) ?: "").startsWith("video")
                    val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    
                    // [반영] 비율이 크면 가로 2칸 점유 가중치를 부여하여 빈 공간을 스스로 메우도록 함
                    val span = if (ratio > 1.4f && id % 3 == 0L) 2 else 1
                    localItems.add(GalleryMedia("local_$id", uri, isVideo, false, ratio, "${w}x${h}", span))
                }
            }
            withContext(Dispatchers.Main) { totalMedia.addAll(localItems) }
        }
        // 온라인 샘플 로딩
        launch(Dispatchers.IO) {
            delay(1000)
            val remoteItems = List(5) { i -> GalleryMedia("online_$i", Uri.parse("https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"), true, true, 1.77f, "1920x1080", 2) }
            withContext(Dispatchers.Main) { totalMedia.addAll(remoteItems) }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 16.dp) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                Button(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(if (isAutoScrollEnabled) "자동 스크롤 중" else "자동 스크롤 시작")
                }
            }
        }
    ) { padding ->
        // [반영] 한 손가락 좌우 스와이프로 탭 이동
        HorizontalPager(
            state = pagerState, 
            modifier = Modifier.padding(padding).fillMaxSize(),
            userScrollEnabled = true 
        ) { pageIdx ->
            val filtered = when (pageIdx) {
                1 -> totalMedia.filter { !it.isVideo }
                2 -> totalMedia.filter { it.isVideo }
                3 -> totalMedia.filter { !it.isOnline }
                4 -> totalMedia.filter { it.isOnline }
                else -> totalMedia
            }
            GapFillingComfortableGrid(filtered, isAutoScrollEnabled, videoImageLoader, initialZoom)
        }
    }
}

@Composable
fun GapFillingComfortableGrid(items: List<GalleryMedia>, isEnabled: Boolean, imageLoader: ImageLoader, initialColumns: Int) {
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    
    // [반영] 두 손가락 줌으로 레이아웃 레벨(칸 수) 변경
    var columnCount by remember { mutableFloatStateOf(initialColumns.toFloat()) }
    val displayColumns = columnCount.toInt().coerceIn(1, 5)
    
    var scrollDirection by remember { mutableIntStateOf(1) }
    var manualPlayId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isEnabled, scrollDirection) {
        if (isEnabled) {
            while (isActive) {
                if (scrollDirection == 1 && !gridState.canScrollForward) scrollDirection = -1
                else if (scrollDirection == -1 && !gridState.canScrollBackward) scrollDirection = 1
                gridState.scrollBy(2.5f * scrollDirection)
                delay(16)
            }
        }
    }

    val activeVideoId by remember {
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

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(displayColumns),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // [반영] 핀치 제스처 감지 및 레이아웃 레벨 변경
                    detectTransformGestures { _, _, zoom, _ ->
                        columnCount = (columnCount / zoom).coerceIn(1f, 5.9f)
                    }
                },
            contentPadding = PaddingValues(1.dp),
            verticalItemSpacing = 1.dp,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(items, key = { it.id }, span = { item ->
                // [반영] 남는 공간에 맞춰 비율을 변경하거나 Span을 확장하여 빈 공간 제거
                if (item.spanWeight > 1 && displayColumns >= 2) {
                    StaggeredGridItemSpan.FullLine 
                } else {
                    StaggeredGridItemSpan.SingleLane
                }
            }) { item ->
                val isPlaying = item.id == manualPlayId || (manualPlayId == null && item.id == activeVideoId)
                SeamlessCard(item, isPlaying, imageLoader) {
                    manualPlayId = if (manualPlayId == item.id) null else item.id
                }
            }
        }

        if (gridState.firstVisibleItemIndex > 2) {
            FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(44.dp)) {
                Icon(Icons.Default.ArrowUpward, null)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SeamlessCard(item: GalleryMedia, isPlaying: Boolean, imageLoader: ImageLoader, onPlayClick: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().wrapContentHeight(), shape = RectangleShape) {
        Box(modifier = Modifier.aspectRatio(item.ratio), contentAlignment = Alignment.Center) {
            if (item.isVideo && isPlaying) {
                VideoPlayerCore(item.uri)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri).memoryCachePolicy(CachePolicy.ENABLED).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color.DarkGray)
                )
                if (item.isVideo) {
                    Icon(Icons.Default.VideoCameraBack, null, tint = Color.White.copy(0.7f), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                    IconButton(onClick = onPlayClick) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                }
            }
            Surface(color = Color.Black.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                Text(text = item.resolutionText, color = Color.White, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 3.dp))
            }
            if (item.isOnline) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(0.8f), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    Text(text = "온라인", color = Color.White, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
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
