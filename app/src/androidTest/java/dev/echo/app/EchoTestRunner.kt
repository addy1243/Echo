package dev.echo.app

import android.app.Activity
import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner

class EchoTestRunner : AndroidJUnitRunner() {
    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        super.newActivity(cl, className, if (className == MainActivity::class.java.name) {
            Intent(intent).putExtra(MainActivity.SKIP_TUTORIAL_EXTRA, true)
        } else intent)
}
