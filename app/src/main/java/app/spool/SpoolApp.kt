package app.spool

import android.app.Application
import android.content.Context
import app.spool.data.ChannelArt
import app.spool.data.FileOps
import app.spool.data.Library
import app.spool.data.ProgressStore
import app.spool.data.Settings
import app.spool.data.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    val channelArt = ChannelArt(app, scope)
    val fileOps = FileOps(app, library, progress)

    init {
        // Quietly fetch YouTube pictures for channels without one: at startup, and again
        // whenever the library changes (e.g. a new channel was downloaded).
        scope.launch {
            library.items.collectLatest { items ->
                if (items.isEmpty()) return@collectLatest
                delay(3_000) // let the UI settle and artist tags resolve first
                channelArt.autoFetch(items)
            }
        }
    }
}

val Context.graph: AppGraph
    get() = (applicationContext as SpoolApp).graph
