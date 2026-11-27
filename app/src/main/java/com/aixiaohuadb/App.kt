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
        // 先注册模式副作用钩子，再初始化容器：消除冷启动隐式顺序依赖，
        // OverlayStore.init 恢复模式时钩子必定已就位，只收敛内存状态不启动服务。
        OverlayStore.setModeApplier { mode, fromRestore -> container.applyControlMode(mode, fromRestore) }
        container.initialize()
        // 悬浮控制中心配置提前加载（含旧 quick_buttons 数据迁移），Service 与设置页共享
        OverlayStore.init(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
