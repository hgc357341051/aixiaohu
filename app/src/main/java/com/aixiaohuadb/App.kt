package com.aixiaohuadb

import android.app.Application
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.service.OverlayStore

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
        // 先注册模式副作用钩子，再加载配置：init 恢复 MANUAL 模式时需立即真实停麦
        OverlayStore.setModeApplier { mode -> container.applyControlMode(mode) }
        // 悬浮控制中心配置提前加载（含旧 quick_buttons 数据迁移），Service 与设置页共享
        OverlayStore.init(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
