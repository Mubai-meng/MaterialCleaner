package me.gm.cleaner.runtime.server.observer

import android.util.Log
import me.gm.cleaner.runtime.server.BuildConfig
import me.gm.cleaner.runtime.server.CleanerServer
import java.util.concurrent.ConcurrentHashMap

object ObserverManager {
    private val observers: MutableMap<Class<*>, BaseObserver> = ConcurrentHashMap()

    fun startAllObservers(server: CleanerServer) {
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "startAllObservers")
        // 逐个隔离启动：构造与 start() 各自独立 try/catch。
        //
        // 旧实现是"先把所有观察器构造进 list、再统一 forEach { start() }"，
        // 只要任何一个构造函数抛异常（实测 DataAppDirObserver 的构造就会走
        // getInstalledPackages，在 API 37 上可能抛 NoSuchMethodError），
        // 就会在 forEach 之前中断——**一个观察器都不启动**。
        // 其中 ActivityManagerLogsObserver 是进程启动事件的唯一来源，
        // 它不启动 → bindMount 永不触发 → 存储重定向静默失效（无任何报错），
        // 因此这里必须让故障面收敛到单个观察器。
        val factories: List<Pair<String, () -> BaseObserver>> = listOf(
            "StorageMountObserver" to { StorageMountObserver() },
            "ActivityManagerLogsObserver" to { ActivityManagerLogsObserver(server) },
            "FileSystemObserver" to { FileSystemObserver(server) },
            "IntentReceiver" to { IntentReceiver() },
            "DataAppDirObserver" to { DataAppDirObserver() },
        )
        var started = 0
        factories.forEach { (name, factory) ->
            try {
                factory().start()
                started++
            } catch (t: Throwable) {
                Log.e(BuildConfig.LIBRARY_PACKAGE_NAME, "observer $name failed to start", t)
            }
        }
        Log.i(
            BuildConfig.LIBRARY_PACKAGE_NAME,
            "startAllObservers: started=$started/${factories.size}"
        )
    }

    fun registerObserver(observer: BaseObserver) {
        observers[observer.javaClass] = observer
    }

    fun unregisterObserver(observer: BaseObserver) {
        observers.remove(observer.javaClass)
    }

    fun getObservers(): Collection<BaseObserver> = observers.values

    @JvmStatic
    fun <T : BaseObserver> fastGetObserver(javaClass: Class<T>): T? =
        observers[javaClass] as T?

    fun <T : BaseObserver> getObserver(javaClass: Class<T>): T? =
        observers.values.firstOrNull { javaClass.isAssignableFrom(it.javaClass) } as T?

    fun stopAllObservers() {
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "stopAllObservers")
        observers.values.forEach { it.stop() }
        observers.clear()
    }
}
