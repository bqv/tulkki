/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

@file:Suppress("DEPRECATION")

package uk.xa0.tulkki.ui.service

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Camera
import android.util.Log
import android.view.TextureView

import com.google.zxing.PlanarYUVLuminanceSource

import uk.xa0.tulkki.xmpp.Config

import java.io.IOException
import java.util.ArrayList
import java.util.Collections

/**
 * @author Andreas Schildbach
 *
 * `@Throws(IOException::class)` keeps the Java method's declared checked exception, so a Java
 * caller written against the old signature keeps compiling and still has to handle it.
 */
@Suppress("DEPRECATION")
class CameraManager {

    private var camera: Camera? = null
    private val cameraInfo = Camera.CameraInfo()
    private var cameraResolution: Camera.Size? = null
    private var frame: Rect? = null
    private var framePreview: RectF? = null

    fun getFrame(): Rect? {
        return frame
    }

    fun getFramePreview(): RectF? {
        return framePreview
    }

    fun getFacing(): Int {
        return cameraInfo.facing
    }

    fun getOrientation(): Int {
        return cameraInfo.orientation
    }

    @Throws(IOException::class)
    fun open(textureView: TextureView, displayOrientation: Int, continuousAutoFocus: Boolean): Camera {
        val cameraId = determineCameraId()
        Camera.getCameraInfo(cameraId, cameraInfo)

        val opened = Camera.open(cameraId)
        camera = opened

        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            opened.setDisplayOrientation((720 - displayOrientation - cameraInfo.orientation) % 360)
        } else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            opened.setDisplayOrientation((720 - displayOrientation + cameraInfo.orientation) % 360)
        } else {
            throw IllegalStateException("facing: " + cameraInfo.facing)
        }

        opened.setPreviewTexture(textureView.getSurfaceTexture())

        val width = textureView.getWidth()
        val height = textureView.getHeight()

        val parameters = opened.getParameters()

        val resolution = findBestPreviewSizeValue(parameters, width, height)
        cameraResolution = resolution

        val rawSize = Math.min(width * 2 / 3, height * 2 / 3)
        val frameSize = Math.max(MIN_FRAME_SIZE, Math.min(MAX_FRAME_SIZE, rawSize))

        val leftOffset = (width - frameSize) / 2
        val topOffset = (height - frameSize) / 2
        val frameRect = Rect(leftOffset, topOffset, leftOffset + frameSize, topOffset + frameSize)
        frame = frameRect

        val widthFactor: Float
        val heightFactor: Float
        val orientedFrame: Rect
        val isTexturePortrait = width < height
        val isCameraPortrait = resolution.width < resolution.height
        if (isTexturePortrait == isCameraPortrait) {
            widthFactor = resolution.width.toFloat() / width
            heightFactor = resolution.height.toFloat() / height
            orientedFrame = Rect(frameRect)
        } else {
            widthFactor = resolution.width.toFloat() / height
            heightFactor = resolution.height.toFloat() / width
            // Swap X and Y coordinates to flip frame to the same orientation as cameraResolution
            orientedFrame = Rect(frameRect.top, frameRect.left, frameRect.bottom, frameRect.right)
        }

        framePreview = RectF(
            orientedFrame.left * widthFactor, orientedFrame.top * heightFactor,
            orientedFrame.right * widthFactor, orientedFrame.bottom * heightFactor)

        val savedParameters = parameters?.flatten()

        try {
            setDesiredCameraParameters(opened, resolution, continuousAutoFocus)
        } catch (x: RuntimeException) {
            if (savedParameters != null) {
                val parameters2 = opened.getParameters()
                parameters2.unflatten(savedParameters)
                try {
                    opened.setParameters(parameters2)
                    setDesiredCameraParameters(opened, resolution, continuousAutoFocus)
                } catch (x2: RuntimeException) {
                    Log.d(Config.LOGTAG, "problem setting camera parameters", x2)
                }
            }
        }

        try {
            opened.startPreview()
            return opened
        } catch (x: RuntimeException) {
            Log.w(Config.LOGTAG, "something went wrong while starting camera preview", x)
            opened.release()
            throw x
        }
    }

    private fun determineCameraId(): Int {
        val cameraCount = Camera.getNumberOfCameras()
        val cameraInfo = Camera.CameraInfo()

        // prefer back-facing camera
        for (i in 0 until cameraCount) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i
            }
        }

        // fall back to front-facing camera
        for (i in 0 until cameraCount) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i
            }
        }

        return -1
    }

    fun close() {
        val active = camera
        if (active != null) {
            try {
                active.stopPreview()
            } catch (x: RuntimeException) {
                Log.w(Config.LOGTAG, "something went wrong while stopping camera preview", x)
            }

            active.release()
        }
    }

    @SuppressLint("InlinedApi")
    private fun setDesiredCameraParameters(
        camera: Camera, cameraResolution: Camera.Size, continuousAutoFocus: Boolean) {
        val parameters = camera.getParameters()
        if (parameters == null) {
            return
        }

        val supportedFocusModes = parameters.getSupportedFocusModes()
        val focusMode = if (continuousAutoFocus) {
            findValue(
                supportedFocusModes, Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO, Camera.Parameters.FOCUS_MODE_AUTO,
                Camera.Parameters.FOCUS_MODE_MACRO)
        } else {
            findValue(
                supportedFocusModes, Camera.Parameters.FOCUS_MODE_AUTO,
                Camera.Parameters.FOCUS_MODE_MACRO)
        }
        if (focusMode != null) {
            parameters.setFocusMode(focusMode)
        }

        parameters.setPreviewSize(cameraResolution.width, cameraResolution.height)

        camera.setParameters(parameters)
    }

    fun requestPreviewFrame(callback: Camera.PreviewCallback) {
        try {
            camera!!.setOneShotPreviewCallback(callback)
        } catch (x: RuntimeException) {
            Log.d(Config.LOGTAG, "problem requesting preview frame, callback won't be called", x)
        }
    }

    fun buildLuminanceSource(data: ByteArray): PlanarYUVLuminanceSource {
        val resolution = cameraResolution ?: throw NullPointerException()
        val preview = framePreview ?: throw NullPointerException()
        return PlanarYUVLuminanceSource(
            data, resolution.width, resolution.height,
            preview.left.toInt(), preview.top.toInt(), preview.width().toInt(),
            preview.height().toInt(), false)
    }

    fun setTorch(enabled: Boolean) {
        val active = camera ?: throw NullPointerException()
        if (enabled != getTorchEnabled(active)) {
            setTorchEnabled(active, enabled)
        }
    }

    private fun getTorchEnabled(camera: Camera): Boolean {
        val parameters = camera.getParameters()
        if (parameters != null) {
            val flashMode = camera.getParameters().getFlashMode()
            return flashMode != null &&
                (Camera.Parameters.FLASH_MODE_ON == flashMode ||
                    Camera.Parameters.FLASH_MODE_TORCH == flashMode)
        }

        return false
    }

    private fun setTorchEnabled(camera: Camera, enabled: Boolean) {
        val parameters = camera.getParameters()

        val supportedFlashModes = parameters.getSupportedFlashModes()
        if (supportedFlashModes != null) {
            val flashMode: String?
            if (enabled) {
                flashMode = findValue(
                    supportedFlashModes, Camera.Parameters.FLASH_MODE_TORCH,
                    Camera.Parameters.FLASH_MODE_ON)
            } else {
                flashMode = findValue(supportedFlashModes, Camera.Parameters.FLASH_MODE_OFF)
            }

            if (flashMode != null) {
                camera.cancelAutoFocus() // autofocus can cause conflict

                parameters.setFlashMode(flashMode)
                camera.setParameters(parameters)
            }
        }
    }

    companion object {
        private const val MIN_FRAME_SIZE = 240
        private const val MAX_FRAME_SIZE = 600
        private const val MIN_PREVIEW_PIXELS = 470 * 320 // normal screen
        private const val MAX_PREVIEW_PIXELS = 1280 * 720

        private val numPixelComparator = Comparator<Camera.Size> { size1, size2 ->
            val pixels1 = size1.height * size1.width
            val pixels2 = size2.height * size2.width

            if (pixels1 < pixels2) {
                1
            } else if (pixels1 > pixels2) {
                -1
            } else {
                0
            }
        }

        private fun findBestPreviewSizeValue(
            parameters: Camera.Parameters, width: Int, height: Int): Camera.Size {
            val w: Int
            val h: Int
            if (height > width) {
                w = height
                h = width
            } else {
                w = width
                h = height
            }

            val screenAspectRatio = w.toFloat() / h.toFloat()

            val rawSupportedSizes = parameters.getSupportedPreviewSizes()
            if (rawSupportedSizes == null) {
                return parameters.getPreviewSize()
            }

            // sort by size, descending
            val supportedPreviewSizes = ArrayList(rawSupportedSizes)
            Collections.sort(supportedPreviewSizes, numPixelComparator)

            var bestSize: Camera.Size? = null
            var diff = Float.POSITIVE_INFINITY

            for (supportedPreviewSize in supportedPreviewSizes) {
                val realWidth = supportedPreviewSize.width
                val realHeight = supportedPreviewSize.height
                val realPixels = realWidth * realHeight
                if (realPixels < MIN_PREVIEW_PIXELS || realPixels > MAX_PREVIEW_PIXELS) {
                    continue
                }

                val isCandidatePortrait = realWidth < realHeight
                val maybeFlippedWidth = if (isCandidatePortrait) realHeight else realWidth
                val maybeFlippedHeight = if (isCandidatePortrait) realWidth else realHeight
                if (maybeFlippedWidth == w && maybeFlippedHeight == h) {
                    return supportedPreviewSize
                }

                val aspectRatio = maybeFlippedWidth.toFloat() / maybeFlippedHeight.toFloat()
                val newDiff = Math.abs(aspectRatio - screenAspectRatio)
                if (newDiff < diff) {
                    bestSize = supportedPreviewSize
                    diff = newDiff
                }
            }

            return bestSize ?: parameters.getPreviewSize()
        }

        private fun findValue(values: Collection<String>, vararg valuesToFind: String): String? {
            for (valueToFind in valuesToFind) {
                if (values.contains(valueToFind)) {
                    return valueToFind
                }
            }

            return null
        }
    }
}
