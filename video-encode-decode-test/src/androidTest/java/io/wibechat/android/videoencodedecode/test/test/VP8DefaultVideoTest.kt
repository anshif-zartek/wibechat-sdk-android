package io.wibechat.android.videoencodedecode.test.test

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.wibechat.android.videoencodedecode.CallActivity
import io.wibechat.android.videoencodedecode.VideoTest
import io.wibechat.android.videoencodedecode.createAndroidIntentComposeRule

class VP8DefaultVideoTest : VideoTest() {
    override val composeTestRule: ComposeTestRule = createAndroidIntentComposeRule<CallActivity> { context ->
        Intent(context, CallActivity::class.java).apply {
            putExtra(
                CallActivity.KEY_ARGS,
                CallActivity.BundleArgs(
                    SERVER_URL,
                    token1,
                    token2,
                    useDefaultVideoEncoderFactory = false,
                    codecWhiteList = listOf("VP8")
                )
            )
        }
    }
}
