package com.wibechat.android.videoencodedecode.test.test

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.wibechat.android.videoencodedecode.CallActivity
import com.wibechat.android.videoencodedecode.VideoTest
import com.wibechat.android.videoencodedecode.createAndroidIntentComposeRule

class NoWhitelistSimulcastVideoTest : VideoTest() {
    override val composeTestRule: ComposeTestRule = createAndroidIntentComposeRule<CallActivity> { context ->
        Intent(context, CallActivity::class.java).apply {
            putExtra(
                CallActivity.KEY_ARGS,
                CallActivity.BundleArgs(
                    SERVER_URL,
                    token1,
                    token2,
                    useDefaultVideoEncoderFactory = false
                )
            )
        }
    }
}
