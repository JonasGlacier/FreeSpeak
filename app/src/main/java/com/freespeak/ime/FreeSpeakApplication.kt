package com.freespeak.ime

import android.app.Application
import com.freespeak.ime.data.AppPreferencesRepository
import com.freespeak.ime.engine.EngineManager
import com.freespeak.ime.model.ModelRepository
import com.freespeak.ime.text.OpenCcConverter

class FreeSpeakApplication : Application() {

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var engineManager: EngineManager
        private set

    lateinit var itnPackRepository: com.freespeak.ime.itn.ItnPackRepository
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = AppPreferencesRepository(this)
        modelRepository = ModelRepository(this)
        itnPackRepository = com.freespeak.ime.itn.ItnPackRepository(this)
        engineManager = EngineManager(modelRepository, OpenCcConverter { name ->
            assets.open("opencc/$name").reader(Charsets.UTF_8)
        }, com.freespeak.ime.itn.NemoItnProcessor(itnPackRepository))
    }
}
