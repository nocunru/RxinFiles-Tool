package com.rxinfiles.tool

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class VideoViewerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var progress: View
    private lateinit var errorText: TextView
    private lateinit var playPauseBtn: TextView
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seekBar: SeekBar

    private val progressHandler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var seeking = false
    private var resumePosition = 0
    private var playWhenPrepared = true
    private var stagedParent: File? = null

    private val progressTask = object : Runnable {
        override fun run() {
            if (prepared && !seeking) {
                val position = videoView.currentPosition.coerceAtLeast(0)
                seekBar.progress = position
                currentTime.text = formatTime(position)
            }
            playPauseBtn.text = if (videoView.isPlaying) "暂停" else "播放"
            progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_viewer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = findViewById(R.id.videoView)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.errorText)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        seekBar = findViewById(R.id.seekBar)

        resumePosition = savedInstanceState?.getInt(STATE_POSITION, 0) ?: 0
        playWhenPrepared = savedInstanceState?.getBoolean(STATE_PLAYING, true) ?: true

        val path = intent.getStringExtra(EXTRA_PATH)
        findViewById<TextView>(R.id.titleText).text = path?.let { File(it).name }.orEmpty()
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener {
            prepared = true
            progress.visibility = View.GONE
            errorText.visibility = View.GONE
            playPauseBtn.isEnabled = true
            seekBar.isEnabled = true
            val duration = videoView.duration.coerceAtLeast(0)
            seekBar.max = duration
            totalTime.text = formatTime(duration)
            if (resumePosition > 0 && resumePosition < duration) {
                videoView.seekTo(resumePosition)
            }
            if (playWhenPrepared) videoView.start()
            playWhenPrepared = false
        }
        videoView.setOnCompletionListener {
            seekBar.progress = seekBar.max
            currentTime.text = formatTime(seekBar.max)
            playPauseBtn.text = "播放"
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "VideoView error what=$what extra=$extra path=$path")
            prepared = false
            playPauseBtn.isEnabled = false
            seekBar.isEnabled = false
            showError("无法播放该视频\n可能是格式不受支持、文件已损坏或无法读取")
            true
        }

        playPauseBtn.setOnClickListener {
            if (!prepared) return@setOnClickListener
            if (videoView.isPlaying) videoView.pause() else videoView.start()
            playPauseBtn.text = if (videoView.isPlaying) "暂停" else "播放"
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seeking = true
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTime.text = formatTime(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                resumePosition = seekBar.progress
                if (prepared) videoView.seekTo(resumePosition)
            }
        })

        if (path.isNullOrEmpty()) {
            showError("没有收到视频路径")
        } else {
            prepare(path)
        }
    }

    private fun prepare(path: String) {
        progress.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        Thread {
            val original = File(path)
            val target = try {
                if (original.isFile && original.canRead()) {
                    original
                } else {
                    FileOps.stageToCache(this, original)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "prepare failed: $path", t)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    if (target != original) target?.parentFile?.deleteRecursively()
                    return@runOnUiThread
                }
                if (target == null) {
                    showError("无法读取该视频\n暂存不可读文件失败")
                } else {
                    if (target != original) stagedParent = target.parentFile
                    videoView.setVideoPath(target.absolutePath)
                }
            }
        }.start()
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (prepared) resumePosition = videoView.currentPosition
        outState.putInt(STATE_POSITION, resumePosition)
        outState.putBoolean(
            STATE_PLAYING,
            if (prepared) videoView.isPlaying else playWhenPrepared
        )
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        progressHandler.removeCallbacks(progressTask)
        progressHandler.post(progressTask)
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressTask)
        playWhenPrepared = false
        if (prepared) {
            resumePosition = videoView.currentPosition
            videoView.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTask)
        videoView.stopPlayback()
        stagedParent?.deleteRecursively()
        stagedParent = null
        super.onDestroy()
    }

    private fun formatTime(millis: Int): String {
        val seconds = (millis.coerceAtLeast(0) / 1000).toLong()
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, rest)
        }
    }

    companion object {
        const val EXTRA_PATH = "video_path"
        private const val TAG = "VideoViewer"
        private const val STATE_POSITION = "video_position"
        private const val STATE_PLAYING = "video_playing"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
