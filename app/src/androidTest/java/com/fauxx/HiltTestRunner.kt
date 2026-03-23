package com.fauxx

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom [AndroidJUnitRunner] that uses [HiltTestApplication] so that Hilt can inject
 * dependencies in instrumented UI tests annotated with [dagger.hilt.android.testing.HiltAndroidTest].
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
