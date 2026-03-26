package com.qa.myphoto


import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class GalleryMedia(
    val id: String,
    val uri: Uri,
    val isVideo: Boolean,
    val isOnline: Boolean,
    val ratio: Float
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PermissionGate { MainAutoLoopGallery() }
            }
        }
    }
}

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { g -> g }
    }
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        launcher.launch(perms)
    }
    if (granted) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainAutoLoopGallery() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = listOf("전체", "사진", "동영상", "단말", "온라인")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    // 자동 스크롤 활성화 여부
    var isAutoScrollActive by remember { mutableStateOf(false) }

    // 단말 파일 로드
    val deviceMedia = remember { mutableStateListOf<GalleryMedia>() }
    LaunchedEffect(Unit) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE)
        context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val isVideo = cursor.getString(mimeCol).startsWith("video")
                val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                deviceMedia.add(GalleryMedia("local_$id", uri, isVideo, false, if(id % 2 == 0L) 0.8f else 1.3f))
            }
        }
    }

    val onlineMedia = remember {
        List(10) { i -> GalleryMedia("online_$i", Uri.parse("https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"), true, true, 1.0f) }
    }
    val totalMedia = (deviceMedia + onlineMedia).shuffled()

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 16.dp) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Text(title, fontSize = 16.sp, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
                Button(
                    onClick = { isAutoScrollActive = !isAutoScrollActive },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Text(if (isAutoScrollActive) "자동 왕복 스크롤 중지" else "자동 왕복 스크롤 시작")
                }
            }
        }
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.padding(padding)) { pageIdx ->
            val filtered = when (pageIdx) {
                1 -> totalMedia.filter { !it.isVideo }
                2 -> totalMedia.filter { it.isVideo }
                3 -> totalMedia.filter { !it.isOnline }
                4 -> totalMedia.filter { it.isOnline }
                else -> totalMedia
            }
            AutoLoopGrid(filtered, isAutoScrollActive)
        }
    }
}

@Composable
fun AutoLoopGrid(items: List<GalleryMedia>, isEnabled: Boolean) {
    val gridState = rememberLazyStaggeredGridState()
    var columnCount by remember { mutableIntStateOf(3) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    val animatedCols by animateIntAsState(columnCount, label = "cols")

    // --- [핵심: 자동 왕복 스크롤 로직] ---
    var scrollDirection by remember { mutableIntStateOf(1) } // 1: 아래, -1: 위

    LaunchedEffect(isEnabled, scrollDirection) {
        if (isEnabled) {
            while (true) {
                // 1. 끝에 도달했는지 체크하여 방향 반전
                if (scrollDirection == 1 && !gridState.canScrollForward) {
                    scrollDirection = -1 // 맨 아래면 위로
                } else if (scrollDirection == -1 && !gridState.canScrollBackward) {
                    scrollDirection = 1 // 맨 위면 아래로
                }

                // 2. 실제 스크롤 수행
                gridState.scrollBy(4f * scrollDirection)
                delay(16)
            }
        }
    }

    // 자동 재생 ID 감지
    val autoPlayId by remember {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { info -> items.getOrNull(info.index)?.isVideo == true }?.key.toString()
        }
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(animatedCols),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    zoomScale *= zoom
                    if (zoomScale > 1.2f && columnCount > 1) { columnCount--; zoomScale = 1f }
                    else if (zoomScale < 0.8f && columnCount < 5) { columnCount++; zoomScale = 1f }
                }
            },
        contentPadding = PaddingValues(2.dp),
        verticalItemSpacing = 2.dp,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(items, key = { it.id }) { item ->
            ComfortableMediaCard(item, isPlaying = item.id == autoPlayId)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ComfortableMediaCard(item: GalleryMedia, isPlaying: Boolean) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
    ) {
        Box(modifier = Modifier.aspectRatio(item.ratio), contentAlignment = Alignment.Center) {
            if (item.isVideo && isPlaying) {
                VideoPlayerCore(item.uri)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri)
                        .decoderFactory(VideoFrameDecoder.Factory()).crossfade(true).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (item.isVideo) Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(40.dp))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerCore(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { player = exoPlayer; useController = false;
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) } },
        modifier = Modifier.fillMaxSize()
    )
}