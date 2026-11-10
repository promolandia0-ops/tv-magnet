package com.promolandia.tvmagnet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.promolandia.tvmagnet.service.StreamService

/** Same player setup for the screen and for the on-device instrumentation test. */
@OptIn(UnstableApi::class)
object PlayerFactory {
    fun build(context:Context,url:String,title:String,onError:(PlaybackException)->Unit={}):ExoPlayer {
        val load=DefaultLoadControl.Builder().setBufferDurationsMs(4_000,20_000,800,2_000).setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(context).setLoadControl(load).build().also {player->
            player.addListener(object:Player.Listener { override fun onPlayerError(error:PlaybackException) { onError(error) } })
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mimeForName(title)).build())
            player.prepare();player.playWhenReady=true
        }
    }
}

@OptIn(UnstableApi::class)
class PlayerActivity:AppCompatActivity() {
    private var exo:ExoPlayer?=null
    private var serviceBound=false
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {/** Kept bound so swiping PlayerActivity away doesn't stop the buffer. */}
        override fun onServiceDisconnected(name:ComponentName?) {}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        title=intent.getStringExtra("title") ?:"Tocar no celular"
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val video=PlayerView(this).apply {
            layoutParams=FrameLayout.LayoutParams(-1,-1)
            setShowNextButton(false);setShowPreviousButton(false)
        }
        root.addView(video)
        exo=PlayerFactory.build(this,intent.getStringExtra("url") ?:"",title.toString()) { error -> toast("Erro ao tocar: ${error.errorCodeName}. Tente tocar na TV.") }.also { video.player=it }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        serviceBound=bindService(Intent(this,StreamService::class.java),connection,Context.BIND_IMPORTANT)
    }
    private fun toast(text:String) { android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show() }
    override fun onPause() { if(isFinishing) exo?.pause(); super.onPause() }
    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exo?.release();exo=null
        if(serviceBound) unbindService(connection)
        super.onDestroy()
    }
}
