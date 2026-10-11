package com.ze.iptvplayer.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Cast SDK setup: the stock "Default Media Receiver", no custom receiver app, no system
 *  notification (the app shows its own "Casting" bar instead). Referenced from the manifest. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(null)
                    .setMediaSessionEnabled(false)
                    .build()
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
