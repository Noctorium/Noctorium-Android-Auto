package app.noctorium.auto

import android.content.ComponentName
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Noctorium on a phone or an emulator, as Android Auto finds it: from another app, through the session the phone app
 * publishes. Needs Noctorium installed (the debug build, unless `-Pandroid.testInstrumentationRunnerArguments.noctorium=`
 * names another) and the network; not run by CI.
 *
 *     ./gradlew :auto:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class RealAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val noctorium = InstrumentationRegistry.getArguments().getString("noctorium") ?: "app.noctorium.debug"
    private lateinit var browser: MediaBrowser

    private fun <T> onMain(work: () -> ListenableFuture<T>): T {
        var future: ListenableFuture<T>? = null
        instrumentation.runOnMainSync { future = work() }
        return future!!.get(60, TimeUnit.SECONDS)
    }

    /** Asks again until [ok] or a minute is up: Home and the radio come over the network. */
    private fun <T> eventually(what: String, ask: () -> T, ok: (T) -> Boolean): T {
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            val got = ask()
            if (ok(got)) return got
            Thread.sleep(1_000)
        }
        fail("Waited a minute for $what")
        throw IllegalStateException()
    }

    private fun children(id: String): List<MediaItem> = onMain { browser.getChildren(id, 0, 100, null) }.value.orEmpty()

    @Before
    fun connect() {
        val token = SessionToken(context, ComponentName(noctorium, "app.noctorium.android.PlaybackService"))
        browser = onMain { MediaBrowser.Builder(context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync {
            browser.pause()
            browser.release()
        }
    }

    @Test
    fun browsesSearchesAndPlaysAsAndroidAutoDoes() {
        val root = onMain { browser.getLibraryRoot(null) }
        assertEquals(CarLibrary.ROOT, root.value!!.mediaId)
        assertTrue(root.params!!.extras.getBoolean(CarLibrary.SEARCH_SUPPORTED))
        assertEquals(listOf("Home", "Library", "Queue", "Radio"), children(CarLibrary.ROOT).map { it.mediaMetadata.title.toString() })

        // Home's rows, read from the services by the app itself.
        val rows = eventually("Home's rows", { children(CarLibrary.HOME) }) { it.isNotEmpty() }
        val songs = children(rows.first().mediaId)
        assertTrue("a row of songs", songs.isNotEmpty() && songs.all { it.mediaMetadata.isPlayable == true })
        println("Home: ${rows.map { it.mediaMetadata.title }} -- first row: ${songs.take(3).map { it.mediaMetadata.title }}")

        // A cover, through the provider, as Android Auto opens it.
        val cover = songs.first().mediaMetadata.artworkUri!!
        assertEquals("content", cover.scheme)
        val bytes = context.contentResolver.openInputStream(cover)!!.use { it.readBytes() }
        assertTrue("a cover's bytes: ${bytes.size}", bytes.size > 1_000)

        children(CarLibrary.LIBRARY)
        val stations = eventually("radio stations", { children(CarLibrary.RADIO) }) { it.isNotEmpty() }
        println("Radio: ${stations.take(3).map { it.mediaMetadata.title }}")

        onMain { browser.search("daft punk one more time", null) }
        val found = eventually("search results", { onMain { browser.getSearchResult("daft punk one more time", 0, 20, null) }.value.orEmpty() }) { it.isNotEmpty() }
        val picked = found.first()
        println("Search: ${found.take(3).map { it.mediaMetadata.title }}")

        // Picked in the car: the app's queue starts it, and the session says so.
        instrumentation.runOnMainSync {
            browser.setMediaItem(picked)
            browser.prepare()
            browser.play()
        }
        eventually("the picked song to play", {
            var state: Pair<String, Boolean>? = null
            instrumentation.runOnMainSync { state = browser.mediaMetadata.title.toString() to (browser.playbackState == Player.STATE_READY && browser.playWhenReady) }
            state!!
        }) { (title, playing) -> playing && title == picked.mediaMetadata.title.toString() }

        // And the queue tab has it as the song playing now.
        val queue = children(CarLibrary.QUEUE)
        assertTrue("the queue shows it playing: ${queue.map { it.mediaMetadata.subtitle }}", queue.any { it.mediaMetadata.subtitle.toString().startsWith("Playing now") && it.mediaMetadata.title == picked.mediaMetadata.title })
    }
}
