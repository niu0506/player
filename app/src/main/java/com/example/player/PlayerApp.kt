package com.example.player

import android.app.Application
import com.example.player.data.PlayerRepository

/**
 * 应用入口：进程启动时最先执行，触发持久化仓库的一次性加载
 * （Room 建库 + 旧 SharedPreferences 数据迁移），保证任何组件
 * （MainActivity / PlayerService）运行时数据已就绪或写入自动排队。
 */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}
