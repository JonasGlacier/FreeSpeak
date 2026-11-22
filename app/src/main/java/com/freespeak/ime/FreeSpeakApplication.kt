package com.freespeak.ime

import android.app.Application
import com.freespeak.ime.data.AppPreferencesRepository
import com.freespeak.ime.engine.EngineManager
import com.freespeak.ime.model.ModelRepository

class FreeSpeakApplication : Application() {

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var engineManager: EngineManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = AppPreferencesRepository(this)
        modelRepository = ModelRepository(this)
        engineManager = EngineManager(modelRepository)
    }
}
