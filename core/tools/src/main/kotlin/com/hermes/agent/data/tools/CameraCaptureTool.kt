package com.hermes.agent.data.tools

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Capture a photo using the on-device camera.
 * Ported from OpenClaw camera specification (docs/nodes/camera.md).
 *
 * Saves the photo to internal storage and returns the local file path
 * so that vision tools or multimodal turns can inspect the captured image.
 */
@Singleton
class CameraCaptureTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "take_photo",
        description = "Capture a photo using the on-device camera (front or back facing) and save it to a local image file. " +
            "Returns the local file path and metadata. Use this when the user asks you to take a photo, look through the camera, " +
            "inspect real-world surroundings, or analyze visual objects in front of the device. " +
            "The returned file path can then be passed to vision_analyze or attached to a message.",
        parameters = listOf(
            ToolParameter(
                name = "facing",
                type = ToolParameterType.STRING,
                description = "Camera lens facing direction: 'back' (default) or 'front'.",
                required = false,
                enumValues = listOf("back", "front"),
            ),
            ToolParameter(
                name = "quality",
                type = ToolParameterType.INTEGER,
                description = "JPEG compression quality from 1 to 100 (default 85).",
                required = false,
            ),
            ToolParameter(
                name = "flash",
                type = ToolParameterType.STRING,
                description = "Flash mode: 'off' (default), 'on', or 'auto'.",
                required = false,
                enumValues = listOf("off", "on", "auto"),
            ),
        ),
        category = "device",
        capabilities = setOf("camera", "deferrable"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val facing = arguments.string("facing")?.lowercase() ?: "back"
        val quality = (arguments.int("quality") ?: 85).coerceIn(1, 100)
        val flash = arguments.string("flash")?.lowercase() ?: "off"

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                return@withContext ToolResult.error("Camera service unavailable on this device", System.currentTimeMillis() - start)
            }

            val targetLensFacing = if (facing == "front") {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == targetLensFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                return@withContext ToolResult.error("No camera found matching facing='$facing'", System.currentTimeMillis() - start)
            }

            val photoFile = createOutputFile()
            val captureSuccess = captureStillImage(cameraManager, cameraId, photoFile, quality, flash)

            if (!captureSuccess || !photoFile.exists() || photoFile.length() == 0L) {
                return@withContext ToolResult.error("Failed to capture image from camera (device may lack camera hardware or permission)", System.currentTimeMillis() - start)
            }

            val resultMsg = "Photo captured successfully.\n" +
                "File path: ${photoFile.absolutePath}\n" +
                "Size: ${photoFile.length() / 1024} KB\n" +
                "Facing: $facing"

            ToolResult.ok(resultMsg, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Timber.tag("CameraTool").e(e, "Camera capture error")
            ToolResult.error("Camera capture failed: ${e.message}", System.currentTimeMillis() - start)
        }
    }

    private fun createOutputFile(): File {
        val dir = File(context.filesDir, "camera").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMG_$timeStamp.jpg")
    }

    private suspend fun captureStillImage(
        cameraManager: CameraManager,
        cameraId: String,
        outputFile: File,
        quality: Int,
        flash: String,
    ): Boolean = withTimeoutOrNull(8000L) {
        val thread = HandlerThread("CameraCaptureThread").apply { start() }
        val handler = Handler(thread.looper)

        try {
            suspendCancellableCoroutine { cont ->
                var imageReader: ImageReader? = null
                var cameraDevice: CameraDevice? = null
                var captureSession: CameraCaptureSession? = null

                fun cleanup() {
                    runCatching {
                        captureSession?.close()
                        cameraDevice?.close()
                        imageReader?.close()
                        thread.quitSafely()
                    }
                }

                imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            try {
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                FileOutputStream(outputFile).use { out ->
                                    out.write(bytes)
                                }
                                cleanup()
                                if (cont.isActive) cont.resume(true)
                            } catch (t: Throwable) {
                                cleanup()
                                if (cont.isActive) cont.resume(false)
                            } finally {
                                image.close()
                            }
                        }
                    }, handler)
                }

                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            cameraDevice = device
                            val surfaces = listOf(imageReader.surface)
                            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.JPEG_QUALITY, quality.toByte())
                                            when (flash) {
                                                "on" -> set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                                                "auto" -> set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                                else -> set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                            }
                                        }
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: android.hardware.camera2.CaptureFailure
                                            ) {
                                                cleanup()
                                                if (cont.isActive) cont.resume(false)
                                            }
                                        }, handler)
                                    } catch (t: Throwable) {
                                        cleanup()
                                        if (cont.isActive) cont.resume(false)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cleanup()
                                    if (cont.isActive) cont.resume(false)
                                }
                            }, handler)
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            cleanup()
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            cleanup()
                            if (cont.isActive) cont.resume(false)
                        }
                    }, handler)
                } catch (t: Throwable) {
                    cleanup()
                    if (cont.isActive) cont.resume(false)
                }
            }
        } catch (t: Throwable) {
            thread.quitSafely()
            false
        }
    } ?: false
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraCaptureToolModule {
    @Binds
    @IntoSet
    abstract fun bindCameraCaptureTool(tool: CameraCaptureTool): Tool
}
