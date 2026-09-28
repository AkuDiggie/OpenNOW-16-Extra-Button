package com.opencloudgaming.opennow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                val dpi = intent.getIntExtra(EXTRA_DPI, 320)

                if (resultCode != 0 && data != null) {
                    startRecording(resultCode, data, width, height, dpi)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OpenNOW")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "OpenNOW_$timestamp.mp4")
            currentFile = file

            val recorder = MediaRecorder(this)
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(width, height)
                setVideoFrameRate(60)
                setVideoEncodingBitRate(12_000_000) // 12 Mbps smooth
                prepare()
            }
            mediaRecorder = recorder

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OpenNOW-Recorder",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            _isRecordingFlow.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording()
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (ignored: Exception) {}
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        _isRecordingFlow.value = false

        // Otomatis refresh agar video langsung muncul di Galeri / Foto HP
        currentFile?.let { file ->
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenNOW Recorder",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, StreamRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNOW Sedang Merekam")
            .setContentText("Menyimpan gameplay ke Galeri (Movies/OpenNOW)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Hentikan", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "opennow_recorder_channel"
        const val NOTIFICATION_ID = 5050
        const val ACTION_START = "com.opencloudgaming.opennow.RECORD_START"
        const val ACTION_STOP = "com.opencloudgaming.opennow.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"

        private val _isRecordingFlow = MutableStateFlow(false)
        val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
            val intent = Intent(context, StreamRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
