package com.adetkov.droid

import android.graphics.Bitmap
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Droid's camera: a live view the owner points at something while they talk or type to Droid, in Dash's frame in
 * the chat and behind the assistant panel. The picture is the frame they saw: taken when they tap the view, or else
 * when their message goes, from their last word ([mark]) rather than from when listening ended.
 */
class Camera {
    /** The picture once taken; the view shows it in place of the live image. */
    var taken by mutableStateOf<Bitmap?>(null)
        private set
    internal var view: PreviewView? = null
    private var marked: Bitmap? = null

    /** Keeps what the view shows now as the picture to send, unless one was taken. Called at each pause in speech. */
    fun mark() { if (taken == null) grab()?.let { marked = it } }

    /** Takes the picture now: a tap on the view, again if one was taken. */
    fun take() { grab()?.let { taken = it }; marked = null }

    /**
     * The picture to send: the one taken, else the one from the last word, else what the view shows now; the view
     * stops on it. Null if the camera hasn't shown anything yet.
     */
    fun picture(): Bitmap? { if (taken == null) taken = marked ?: grab(); return taken }

    /** Starts over with the live image. */
    fun reset() { taken = null; marked = null }

    private fun grab(): Bitmap? = view?.bitmap
}

/** [camera]'s view, filling [modifier]; a tap on it calls [onTap]. The camera runs only while this is shown. */
@Composable
fun CameraView(camera: Camera, onTap: () -> Unit, modifier: Modifier = Modifier, hint: Boolean = true) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(0)  // the preview only: the picture is what the view shows
            isTapToFocusEnabled = false  // a tap takes the picture; pinching zooms
            // 16:9 is cropped least on a tall screen, so the frame keeps the most of the camera's pixels.
            previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
        }
    }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose { controller.unbind(); camera.view = null }
    }
    val tap by rememberUpdatedState(onTap)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(camera.taken) { if (camera.taken != null) { flash.snapTo(.6f); flash.animateTo(0f, tween(240)) } }
    Box(modifier.background(Color.Black)) {
        AndroidView({ c ->
            PreviewView(c).apply {
                // A TextureView: clipped to the frame's corners, and its pixels can be read back.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
                setOnClickListener { tap() }
                contentDescription = "Camera. Tap to take the picture"
                camera.view = this
            }
        }, Modifier.fillMaxSize())
        camera.taken?.let { Image(it.asImageBitmap(), "The picture", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        if (hint) CameraHint(camera, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

/** What a tap on [camera]'s view does, in a pill over it. */
@Composable
fun CameraHint(camera: Camera, modifier: Modifier = Modifier) {
    Text(if (camera.taken == null) "Tap to take it" else "Tap to take it again",
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x8C0D0D0D)).padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White, style = MaterialTheme.typography.labelLarge)
}
