package app.spool

import android.app.Application
import android.content.Context
import app.spool.data.FileOps
import app.spool.data.Library
import app.spool.data.ProgressStore
import app.spool.data.Settings
import app.spool.data.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SpoolApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Tiny hand-rolled dependency container. Everything lives as long as the process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val progress = ProgressStore(app, scope)
    val library = Library(app, settings, scope)
    val thumbnails = Thumbnails(app)
    val fileOps = FileOps(app, library, progress)
}

val Context.graph: AppGraph
    get() = (applicationContext as SpoolApp).graph
