package me.gm.cleaner.client.ui

import android.os.Bundle
import me.gm.cleaner.app.BaseActivity
import me.gm.cleaner.client.SelfHidingPreferences
import me.gm.cleaner.databinding.ServiceSettingsActivityBinding

class ServiceSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ServiceSettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 必须在 attach 之后（这里是 onCreate）才能拿到 appTasks。
        // 幂等：开关关闭时内部直接 return。
        SelfHidingPreferences.applyRecentsOnCreate(this)
    }
}
