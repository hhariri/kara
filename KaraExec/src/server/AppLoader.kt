package kara.app

import java.net.URLClassLoader
import java.io.*
import kara.server.FileWatchListener
import java.util.ArrayList
import kara.controllers.Dispatcher
import kara.controllers.BaseController
import kara.server.FileWatcher
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kara.config.AppConfig
import org.apache.log4j.Logger

/** Interface for object that want to listen for when an app is loaded.
 */
trait AppLoadListener {
    fun onAppLoaded(application : Application)
}


/** Controls the loading and reloading of a Kara app from a directory.
 */
class AppLoader(val appConfig : AppConfig) : FileWatchListener {

    val logger = Logger.getLogger(this.javaClass)!!

    val binDir = File(appConfig.appRoot, "bin")

    var classLoader : URLClassLoader? = null

    /** This lock is held while loading and retreiving the application to avoid someone retrieving an invalid application. **/
    val appLock : jet.Any = Object()

    var application : Application? = null
//        set(value) = synchronized(appLock) {$application = value}
//        get() = synchronized(appLock) {$application}

    val listeners : MutableList<AppLoadListener> = ArrayList<AppLoadListener>()

    val watcher = FileWatcher(File(appConfig.appRoot, "tmp").toString(), "restart.txt")
    val watchExecutor = Executors.newFixedThreadPool(1)

    public fun addListener(listener : AppLoadListener) {
        listeners.add(listener)
    }

    override fun onFileWatch(dir: String, fileName : String) {
        this@AppLoader.loadApp()
    }

    public fun init() {
        watcher.addListener(this)
        watchExecutor.execute(watcher)
    }

    /** Loads the controllers from the controllers directory into the current application's dispatcher.
     */
    fun loadControllers() {
        if (application == null || application?.dispatcher == null)
            throw RuntimeException("Trying to load controllers without a valid application or dispatcher")
        val dispatcher = application?.dispatcher as Dispatcher
        val controllerDir = File(binDir, "${appConfig.appPackagePath}${File.separator}controllers")
        if (!controllerDir.exists()) {
            throw RuntimeException("App ${appConfig.appPackage} does not have a controllers directory (should be ${controllerDir.toString()})")
        }
        val controllerFilter = object : FileFilter {
            public override fun accept(p0: File): Boolean {
                val fileName = p0.toString()
                return fileName.endsWith(".class") && !fileName.contains("$") // the kotlin compiler has been spitting out these extra class files that aren't actually controllers
            }
        }
        for (val controllerFile in controllerDir.listFiles(controllerFilter)!!) {
            val controllerName = (controllerFile.getName()).replace(".class", "")
            logger.debug("Loading controller ${controllerName}")
            val controllerClass = classLoader?.loadClass("${appConfig.appPackage}.controllers.${controllerName}")
            if (controllerClass == null)
                throw RuntimeException("Expecting ${controllerFile} to declare ${controllerName}")
            dispatcher.parseController(controllerClass as Class<BaseController>)
        }
    }

    /** Loads the application object from the filesystem.
     */
    public fun loadApp() {
        if (classLoader != null) {
            classLoader?.close()
            classLoader = null
        }

        synchronized(appLock) {
            // load the application class
            val url = binDir.toURL()
            classLoader = URLClassLoader(array(url))
            val appClassObject = classLoader?.loadClass("${appConfig.appPackage}.Application")
            if (appClassObject == null)
                throw RuntimeException("Expected class ${appConfig.appPackage}.Application to be defined")
            val appClass = appClassObject as Class<Application>
            application = appClass.newInstance()
            application?.init(appConfig) // this breaks the runtime!!
            logger.debug("Application class: ${application.javaClass.toString()}")

            loadControllers()

            for (val listener in listeners) {
                listener.onAppLoaded(application as Application)
            }
        }
    }
}