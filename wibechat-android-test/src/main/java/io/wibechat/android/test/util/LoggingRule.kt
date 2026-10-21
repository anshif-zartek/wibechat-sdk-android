/*
 * Copyright 2023-2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.wibechat.android.test.util

import android.util.Log
import io.wibechat.android.WibeChat
import io.wibechat.android.util.WCDebugTree
import io.wibechat.android.util.WCLog
import io.wibechat.android.util.LoggingLevel
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Add this rule to a test class to turn on logs.
 */
class LoggingRule : TestRule {

    companion object {

        val logger = object : WCLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                printlnTree.prepareLog(priority.toAndroidLogPriority(), t, message)
            }
        }
        val printlnTree = object : WCDebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                val priorityChar = when (priority) {
                    Log.VERBOSE -> "v"
                    Log.DEBUG -> "d"
                    Log.INFO -> "i"
                    Log.WARN -> "w"
                    Log.ERROR -> "e"
                    Log.ASSERT -> "a"
                    else -> "?"
                }

                println("$priorityChar: $tag: $message")
                if (t != null) {
                    println(t.toString())
                }
            }
        }
    }

    override fun apply(base: Statement, description: Description?) = object : Statement() {
        override fun evaluate() {
            val oldLoggingLevel = WibeChat.loggingLevel
            val oldLogger = WibeChat.logger
            WibeChat.loggingLevel = LoggingLevel.VERBOSE
            WibeChat.logger = logger
            base.evaluate()
            WibeChat.loggingLevel = oldLoggingLevel
            WibeChat.logger = oldLogger
        }
    }
}
