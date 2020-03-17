package org.vourhey.kotlinwebserver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.DisplayMetrics
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

//const val TAKE_PICTURE_ACTION = "com.vourhey.kotlinwebserver.take_picture_action"
//const val TAKE_PICTURE_EXTRA = "com.vourhey.kotlinwebserver.take_picture_extra"
//const val TAKE_PICTURE_VALUE = "com.vourhey.kotlinwebserver.shoot"

const val TAKE_PICTURE_ACTION = 1213
const val TAKE_PICTURE_RESULT = 1214

fun ImageButton.simulateClick(delay: Long = ANIMATION_FAST_MILLIS) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}

/**
 * A simple [Fragment] subclass.
 * Use the [CameraFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CameraFragment : Fragment() {
    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var outputDirectory: File
    private lateinit var appNanoHTTPD: AppNanoHTTPD
    //private lateinit var broadcastManager: LocalBroadcastManager
    private val mHandler : Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when(msg.what) {
                TAKE_PICTURE_ACTION -> {
                    Log.i("CameraFragment", "Taking Picture")
                    val shutter = container.findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
                TAKE_PICTURE_RESULT -> {
                    Log.i("CameraFragment", "Result")
                    msg.data.getString("uri")?.let { appNanoHTTPD.setResult(it) }
                }
            }

        }
    }

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

//    private val takePictureReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            when (intent.getStringExtra(TAKE_PICTURE_EXTRA)) {
//                TAKE_PICTURE_VALUE -> {
//                    val shutter = container.findViewById<ImageButton>(R.id.camera_capture_button)
//                    shutter.simulateClick()
//                }
//            }
//        }
//    }



    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }



    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        Log.i("CameraFragmetn", appNanoHTTPD.isAlive.toString())
        if(!appNanoHTTPD.isAlive) {
            appNanoHTTPD.start()
        }


        if (!Permissions.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(R.id.action_camera_fragment_to_permissions)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i("CameraFragment", "onPause")
        appNanoHTTPD.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
        Log.i("CameraFragment", "OnDestroy")
        appNanoHTTPD.stop()

        // Unregister the broadcast receivers and listeners
        //broadcastManager.unregisterReceiver(takePictureReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        //broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        //val filter = IntentFilter().apply { addAction(TAKE_PICTURE_ACTION) }
        //broadcastManager.registerReceiver(takePictureReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        appNanoHTTPD = getIpAccess(view)?.let { AppNanoHTTPD(it, 31333, mHandler) }!!
//        try {
//            appNanoHTTPD.start()
//        } catch (e: Exception) {
//            Log.i("MainActivity", e.toString())
//        }

        // Wait for the views to be properly laid out
        viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Bind use cases
            bindCameraUseCases()
        }
    }

    private fun takePictureCallback() {
        Log.i("takePictureCallback", "Taking picture")
        val shutter = container.findViewById<ImageButton>(R.id.camera_capture_button)
        shutter.simulateClick()
    }

    private fun getIpAccess(view: View): String? {
        val wifiManager = activity!!.getSystemService(WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        val formatedIpAddress = String.format("%d.%d.%d.%d", ipAddress and 0xff, ipAddress shr 8 and 0xff, ipAddress shr 16 and 0xff, ipAddress shr 24 and 0xff)
        Log.i("getIpAccess", "http://$formatedIpAddress")
        return formatedIpAddress
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.previewSurfaceProvider)

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            // ImageAnalysis
//            imageAnalyzer = ImageAnalysis.Builder()
//                // We request aspect ratio but no resolution
//                .setTargetAspectRatio(screenAspectRatio)
//                // Set initial target rotation, we will have to call this again if rotation changes
//                // during the lifecycle of this use case
//                .setTargetRotation(rotation)
//                .build()
//                // The analyzer can then be assigned to the instance
//                .also {
//                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
//                        // Values returned from our analyzer are passed to the attached listener
//                        // We log image analysis results here - you should do something useful
//                        // instead!
//                        Log.d(TAG, "Average luminosity: $luma")
//                    })
//                }

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        // Inflate a new view containing all UI for controlling the camera
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        // Listener for button used to capture photo
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = ImageCapture.Metadata().apply {

                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            val message = Message()
                            message.what = TAKE_PICTURE_RESULT
                            var bundle = Bundle()
                            bundle.putString("uri", savedUri.toString())
                            message.data = bundle
                            mHandler.sendMessage(message)


                            // If the folder selected is an external media directory, this is
                            // unnecessary but otherwise other apps will not be able to access our
                            // images unless we scan them using [MediaScannerConnection]
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toString()),
                                arrayOf(mimeType)
                            ) { _, uri ->
                                Log.d(TAG, "Image capture scanned into media store: $uri")
                            }
                        }
                    })

                // We can only change the foreground Drawable using API level 23+ API

                // Display flash animation to indicate that photo was captured
                container.postDelayed({
                    container.foreground = ColorDrawable(Color.WHITE)
                    container.postDelayed(
                        { container.foreground = null }, ANIMATION_FAST_MILLIS)
                }, ANIMATION_SLOW_MILLIS)
            }
        }

        // Listener for button used to switch cameras
        controls.findViewById<ImageButton>(R.id.camera_switch_button).setOnClickListener {
            lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            // Re-bind use cases to update selected camera
            bindCameraUseCases()
        }

        // Listener for button used to view the most recent photo
//        controls.findViewById<ImageButton>(R.id.photo_view_button).setOnClickListener {
//            // Only navigate when the gallery has photos
//            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
//                Navigation.findNavController(
//                    requireActivity(), R.id.fragment_container
//                ).navigate(R.id.CameraFragmentDirections
//                    .actionCameraToGallery(outputDirectory.absolutePath))
//            }
//        }
    }

    companion object {

        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)
    }
}
