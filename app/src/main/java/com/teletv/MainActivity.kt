package com.teletv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import com.teletv.ui.App
import com.teletv.ui.theme.TeleTvTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A TV player must keep the display awake while it is in the foreground —
        // otherwise Android TV's sleep policy blanks the screen mid-playback
        // (which presents as a mysterious "black screen").
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TeleTvTheme {
                App()
            }
        }
    }
}
