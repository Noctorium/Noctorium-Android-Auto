package app.noctorium.auto

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The car's side of it, end to end: a Media3 browser -- what Android Auto connects with -- against a library session
 * built as the phone app builds its own, over a real ExoPlayer wrapped as the app wraps it.
 */
@RunWith(RobolectricTestRunner::class)
class CarSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val music = NotedMusic()
    private lateinit var exo: ExoPlayer

    /** What the session handed the player: Noctorium-Mobile's QueueAwarePlayer lets these go by, as this does. */
    private val handedToPlayer = mutableListOf<String>()
    private lateinit var callback: CarLibraryCallback
    private lateinit var session: MediaLibrarySession
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        exo = ExoPlayer.Builder(context).build()
        val player = object : ForwardingPlayer(exo) {
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
                handedToPlayer += mediaItems.map { it.mediaId }
            }
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
                handedToPlayer += mediaItems.map { it.mediaId }
            }
            override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
                handedToPlayer += mediaItems.map { it.mediaId }
            }
        }
        callback = CarLibraryCallback(CarLibrary(music) { url -> url?.let { Uri.parse("content://covers/${CarArtwork.tokenOf(it)}") } })
        session = MediaLibrarySession.Builder(context, player, callback).setId("car-test").build()
        browser = MediaBrowser.Builder(context, session.token).buildAsync().await()
    }

    @After
    fun disconnect() {
        browser.release()
        session.release()
        callback.release()
        exo.release()
    }

    private fun <T> ListenableFuture<T>.await(): T {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (isDone) return get()
            Thread.sleep(2)
        }
        fail("Media3 never answered")
    }

    private fun idle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(2)
    }

    @Test
    fun `the car finds the library, with search, and its four tabs`() {
        val root = browser.getLibraryRoot(LibraryParams.Builder().build()).await()
        assertEquals(CarLibrary.ROOT, root.value!!.mediaId)
        assertTrue(root.params!!.extras.getBoolean(CarLibrary.SEARCH_SUPPORTED))
        val tabs = browser.getChildren(CarLibrary.ROOT, 0, Int.MAX_VALUE, null).await().value!!
        assertEquals(listOf("Home", "Library", "Queue", "Radio"), tabs.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `a song picked in the car plays through the queue, and the player is not handed a song of its own`() {
        val row = browser.getChildren(CarLibrary.HOME, 0, 50, null).await().value!!.first()
        val songs = browser.getChildren(row.mediaId, 0, 50, null).await().value!!
        browser.setMediaItem(songs[2])
        browser.prepare()
        browser.play()
        idle()
        assertEquals(listOf("home h3 of 3"), music.played)
        // Media3 passes the pick on to the player as well -- with the play after it, again -- and the app's player lets
        // it go by. Only the song picked, never the queue's old one.
        assertEquals(listOf(songs[2].mediaId), handedToPlayer.distinct())
    }

    @Test
    fun `the car's search box finds songs to pick`() {
        browser.search("daft punk", null).await()
        val found = browser.getSearchResult("daft punk", 0, 20, null).await().value!!
        assertEquals(listOf("Song x1", "Song x2"), found.map { it.mediaMetadata.title.toString() })
        browser.setMediaItem(found[1])
        browser.play()
        idle()
        assertEquals(listOf("search x2 of 2"), music.played)
    }

    @Test
    fun `a voice asking for a song plays the first found`() {
        val asked = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("around the world").build())
            .build()
        browser.setMediaItem(asked)
        browser.play()
        idle()
        assertEquals(listOf("search x1 of 2"), music.played)
    }

    @Test
    fun `pages of a long list come a page at a time`() {
        music.recentSongs = (1..25).map { testSong("long$it") }
        val first = browser.getChildren(CarLibrary.RECENT, 0, 10, null).await().value!!
        val third = browser.getChildren(CarLibrary.RECENT, 2, 10, null).await().value!!
        assertEquals(10, first.size)
        assertEquals(listOf("Song long21", "Song long22", "Song long23", "Song long24", "Song long25"), third.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `the car's play with nothing loaded picks the queue up again, and with a song loaded leaves it be`() {
        browser.play()
        idle()
        assertEquals(listOf("jump 1"), music.played)

        val again = NotedMusic().apply { loaded = true }
        val loadedSession = MediaLibrarySession.Builder(context, exo, CarLibraryCallback(CarLibrary(again))).setId("car-test-loaded").build()
        val second = MediaBrowser.Builder(context, loadedSession.token).buildAsync().await()
        second.play()
        idle()
        assertEquals(emptyList(), again.played)
        second.release()
        loadedSession.release()
    }

    @Test
    fun `a car starting up is offered what was playing`() {
        val recent = browser.getLibraryRoot(LibraryParams.Builder().setRecent(true).build()).await().value!!
        val offered = browser.getChildren(recent.mediaId, 0, 10, null).await().value!!
        assertEquals(listOf("queue/1"), offered.map { it.mediaId })
        assertEquals(Player.STATE_IDLE, browser.playbackState)
    }
}
