package android.mohsin.assignment_cloudprimero.feature_posedetection.presentation.posedetection_activity

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.mohsin.assignment_cloudprimero.R
import android.mohsin.assignment_cloudprimero.core.Coroutines
import android.mohsin.assignment_cloudprimero.databinding.ActivityPoseDetectionBinding
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.Model.BoundingBoxColor
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.helper.Draw
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.helper.MoviePlayer
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.helper.SpeedControlCallback
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.helper.URIPathHelper
import android.mohsin.assignment_cloudprimero.feature_posedetection.domian.interfaces.VideoFirstFrameCallBackSetter
import android.mohsin.assignment_cloudprimero.feature_posedetection.presentation.BaseActivity
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.text.DecimalFormat
import kotlin.math.acos
import kotlin.math.pow

class PoseDetectionActivity : BaseActivity(), TextureView.SurfaceTextureListener,
    MoviePlayer.PlayerFeedback, VideoFirstFrameCallBackSetter, View.OnClickListener {
    private lateinit var poseDetector: PoseDetector
    private var mPlayTask: MoviePlayer.PlayTask? = null
    private lateinit var uri: Uri
    private var mSurfaceTextureReady = false
    lateinit var binding: ActivityPoseDetectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_pose_detection)
        binding.btnSelectFile.setOnClickListener(this)
        binding.btnPlay.setOnClickListener(this)

        initPoseDetector()
    }

    private fun initPoseDetector() {
        binding.mTextureView.surfaceTextureListener = this

        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()

        poseDetector = PoseDetection.getClient(options)
    }

    override fun onPause() {
        super.onPause()
        // We're not keeping track of the state in static fields, so we need to shut the
        // playback down.  Ideally we'd preserve the state so that the player would continue
        // after a device rotation.
        //
        // We want to be sure that the player won't continue to send frames after we pause,
        // because we're tearing the view down.  So we wait for it to stop here.
        if (mPlayTask != null) {
            stopPlayback()
            mPlayTask!!.waitForStop()
        }
    }

    private fun stopPlayback() {
        mPlayTask?.requestStop()
    }

    fun clickPlayStop() {

        if (mPlayTask != null) {
            return
        }

        binding.btnSelectFile.visibility = View.GONE
        binding.btnPlay.visibility = View.GONE

        val callback = SpeedControlCallback()
        val st: SurfaceTexture = binding.mTextureView.getSurfaceTexture()!!
        val surface = Surface(st)
        var player: MoviePlayer? = null
        Log.d(
            "FILE:",
            "" + Environment.getExternalStorageDirectory().absolutePath
                    + "/video.mp4" + "  "
        )

        if (uri != null) {
            val uriPathHelper = URIPathHelper()
            val path = uriPathHelper.getPath(applicationContext, uri)
            try {
                player = MoviePlayer(File(path), surface, callback)
            } catch (ioe: IOException) {
                surface.release()
                return
            }
            adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight())
            mPlayTask = MoviePlayer.PlayTask(player, this)
            mPlayTask!!.execute()
        } else {

        }

    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val viewWidth = binding.mTextureView.width
        val viewHeight = binding.mTextureView.height
        val aspectRatio = videoHeight.toDouble() / videoWidth
        val newWidth: Int
        val newHeight: Int
        if (viewHeight > (viewWidth * aspectRatio).toInt()) {
            // limited by narrow width; restrict height
            newWidth = viewWidth
            newHeight = (viewWidth * aspectRatio).toInt()
        } else {
            // limited by short height; restrict width
            newWidth = (viewHeight / aspectRatio).toInt()
            newHeight = viewHeight
        }
        val xoff = (viewWidth - newWidth) / 2
        val yoff = (viewHeight - newHeight) / 2
        val txform = Matrix()
        binding.mTextureView.getTransform(txform)
        txform.setScale(
            newWidth.toFloat() / viewWidth,
            newHeight.toFloat() / viewHeight
        )
        //txform.postRotate(10);          // just for fun
        txform.postTranslate(xoff.toFloat(), yoff.toFloat())
        binding.mTextureView.setTransform(txform)
    }

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
        TODO("Not yet implemented")
    }


    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

        val bm = binding.mTextureView.bitmap

        if (bm != null) {
            Log.d("LOG:", "not null")
            val inputImage = InputImage.fromBitmap(bm, 0)

            poseDetector.process(inputImage)

                .addOnSuccessListener { pose ->

                    Coroutines.main {
                        if (binding.parentLayout.childCount > 4) binding.parentLayout.removeViewAt(4)

                        binding.imgView.setImageBitmap(bm)

                        if (!pose.allPoseLandmarks.isEmpty()) {
                            val BoundingBoxColor = getDominantColor(bm, pose)

                            binding.angle.text =
                                DecimalFormat("##.#").format(findAngle(pose)).toString()

                            val draw = Draw(applicationContext, pose, BoundingBoxColor)
                            binding.parentLayout.addView(draw)
                        }
                        Log.d("LOG:", "Success")
                    }
                }
                .addOnFailureListener { Log.d("LOG:", "Failure") }
        } else Log.d("LOG:", "null")
    }


    fun getDominantColor(bitmap: Bitmap?, pose: Pose): BoundingBoxColor {
        try {
            val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)!!
            val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!
            val colorTop =
                bitmap!!.getPixel(leftShoulder.position.x.toInt(), leftShoulder.position.y.toInt())
            val colorBottom =
                bitmap!!.getPixel(leftKnee.position.x.toInt(), leftKnee.position.y.toInt())
            return BoundingBoxColor(colorTop, colorBottom)
        } catch (e: Exception) {
            e.localizedMessage
        }
        return BoundingBoxColor(1, 1)
    }

    fun findAngle(pose: Pose): Double {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)!!
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)!!
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)!!
//         Shoulder
        val P1 =
            doubleArrayOf(leftShoulder.position.x.toDouble(), leftShoulder.position.y.toDouble())
//         Hip
        val P2 = doubleArrayOf(leftHip.position.x.toDouble(), leftHip.position.y.toDouble())
//         Wrist
        val P3 = doubleArrayOf(leftWrist.position.x.toDouble(), leftWrist.position.y.toDouble())

        val P12 = Math.sqrt((P1[0] - P2[0]).pow(2) + (P1[1] - P2[1]).pow(2))
        val P13 = Math.sqrt((P1[0] - P3[0]).pow(2) + (P1[1] - P3[1]).pow(2))
        val P23 = Math.sqrt((P2[0] - P3[0]).pow(2) + (P2[1] - P3[1]).pow(2))

        val radians_angle = acos((P12.pow(2) + P13.pow(2) - P23.pow(2)) / (2 * P12 * P13))
        val degree_angle = radians_angle * 180 / Math.PI
//         96.9091220273197


//        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
//        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
//
//        var deltaX = leftWrist.position.x - leftShoulder.position.x
//        var deltaY = leftWrist.position.y - leftShoulder.position.y
//        var rad = Math.atan2(deltaY.toDouble(), deltaX.toDouble()); // In radians
//        var deg = rad * (180 / Math.PI);
        return degree_angle
    }

    override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
        mSurfaceTextureReady = false
        // assume activity is pausing, so don't need to update controls
        return true
    }

    override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {

        mSurfaceTextureReady = true
        val bm = binding.mTextureView.bitmap
        binding.imgView.setImageBitmap(bm)
    }

    override fun playbackStopped() {

    }

    override fun setFirstFrame(uri: Uri) {
        this.uri = uri
        val mediaMetadataRetriever = MediaMetadataRetriever()
        mediaMetadataRetriever.setDataSource(applicationContext, uri)
        binding.imgView.setImageBitmap(mediaMetadataRetriever.getFrameAtIndex(0))
        binding.btnPlay.visibility = View.VISIBLE
        binding.btnSelectFile.visibility = View.GONE
    }

    override fun onClick(view: View?) {
        when (view) {
            binding.btnSelectFile -> selectFile(this)
            binding.btnPlay -> clickPlayStop()
        }
    }

}