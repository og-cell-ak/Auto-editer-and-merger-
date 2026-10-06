package com.futurethinking.aivideodirector

import android.app.Application
import androidx.work.Configuration

class EditorApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration =
        Configuration.Builder()
            .setDefaultProcessName(packageName)
            .build()
}
