package com.tommyapps.recorder

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Invisible helper that opens Android 17's system eyedropper and hands the colour to the pen. */
class EyeDropperActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val color = if (r.resultCode == Activity.RESULT_OK)
            r.data?.takeIf { it.hasExtra(Intent.EXTRA_COLOR) }?.getIntExtra(Intent.EXTRA_COLOR, 0)
        else null
        OverlayService.onPicked?.invoke(color)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 37) launcher.launch(Intent(Intent.ACTION_OPEN_EYE_DROPPER))
        else finish()
    }
}