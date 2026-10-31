package com.quietsentry.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 黑屏记录：屏幕保持点亮但显示纯黑，不显示取景预览，单击任意位置拍照。
 * 不做远程值守，也不在后台采集——这样“屏幕不发光”与“采集可确认”两个约束不会互相拉扯。
 */
class BlackScreenActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var gestureDetector: GestureDetector

    private val galleryDir by lazy { File(filesDir, "private_gallery").apply { mkdirs() } }

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera() else statusText.text = getString(R.string.camera_permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 黑屏页会直接落私人照片，必须先过应用锁；校验通过后 recreate 再正常加载
        if (!PrivacyGuard.allow(this)) {
            PrivacyGuard.prompt(this) { ok -> if (ok) recreate() else finish() }
            return
        }
        setContentView(R.layout.activity_black_screen)

        // 屏幕要亮但内容全黑，所以保持常亮而不是关闭屏幕
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.black_status_text)
        statusText.text = getString(R.string.black_hint)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                takePhoto()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                switchLens()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // MVP 用长按退出；PRD 的三指点按退出在触控代价更高时再补
                finish()
            }
        })

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) bindCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val capture = ImageCapture.Builder().build()
            imageCapture = capture
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector(), capture)
            } catch (e: Exception) {
                statusText.text = getString(R.string.camera_open_failed, e.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCamera()
        statusText.text = getString(
            if (lensFacing == CameraSelector.LENS_FACING_BACK) R.string.lens_back else R.string.lens_front
        )
    }

    private fun selector(): CameraSelector =
        CameraSelector.Builder().requireLensFacing(lensFacing).build()

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(galleryDir, "pic_$stamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    statusText.text = getString(R.string.photo_saved, file.name)
                    // 目录遍历放在后台线程，避免刚拍完就影响黑屏页后续的手势响应
                    Thread { StorageKeeper.enforce(this@BlackScreenActivity) }.start()
                }

                override fun onError(e: ImageCaptureException) {
                    statusText.text = getString(R.string.photo_failed, e.message ?: "")
                }
            }
        )
    }
}
