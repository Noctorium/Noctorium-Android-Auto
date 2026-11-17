package app.noctorium.auto

import android.net.Uri
import androidx.media3.common.MediaMetadata
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.QueueState
import app.noctorium.playlists.LocalPlaylist
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The car's menu over a Noctorium that only keeps notes of what it was asked to play. */
@RunWith(RobolectricTestRunner::class)
class CarLibraryTest {

    private val notes = NotedMusic()
    private val library = CarLibrary(notes) { url -> url?.let { Uri.parse("content://covers/${CarArtwork.tokenOf(it)}") } }

    @Test
    fun `four tabs, as a car shows`() = runTest {
        val tabs = library.children(CarLibrary.ROOT)!!
        assertEquals(listOf("Home", "Library", "Queue", "Radio"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        assertEquals(CarLibrary.GRID, tabs.first().mediaMetadata.extras!!.getInt(CarLibrary.CONTENT_STYLE_BROWSABLE))
    }

    @Test
    fun `home's rows open to their songs, and a song plays with home's rules`() = runTest {
        val rows = library.children(CarLibrary.HOME)!!
        assertEquals(listOf("Fresh", "Chill"), rows.map { it.mediaMetadata.title.toString() })
        assertEquals("On YouTube Music", rows[0].mediaMetadata.subtitle.toString())
        val songs = library.children(rows[0].mediaId)!!
        assertEquals(listOf("Song h1", "Song h2", "Song h3"), songs.map { it.mediaMetadata.title.toString() })
        assertEquals("Artist h2", songs[1].mediaMetadata.artist.toString())
        assertTrue(songs.all { it.mediaMetadata.isPlayable == true })
        // Covers go through the provider, never as the web address itself.
        assertEquals("content", songs[0].mediaMetadata.artworkUri!!.scheme)

        assertTrue(library.play(songs[1].mediaId))
        assertEquals(listOf("home h2 of 3"), notes.played)
    }

    @Test
    fun `the library lists recently played and the playlists, and plays from where a song was picked`() = runTest {
        val items = library.children(CarLibrary.LIBRARY)!!
        assertEquals(listOf("Recently played", "Road trip", "Discover"), items.map { it.mediaMetadata.title.toString() })
        assertEquals("2 songs", items[1].mediaMetadata.subtitle.toString())

        val recent = library.children(items[0].mediaId)!!
        assertTrue(library.play(recent[1].mediaId))

        // A playlist id with a slash and spaces in it survives the trip through the car.
        val road = library.children(items[1].mediaId)!!
        assertEquals(listOf("Song p1", "Song p2"), road.map { it.mediaMetadata.title.toString() })
        assertTrue(library.play(road[1].mediaId))

        // A service playlist's songs are read when it is opened, once.
        val discover = library.children(items[2].mediaId)!!
        assertEquals(2, discover.size)
        library.children(items[2].mediaId)
        assertEquals(1, notes.readPlaylists)
        assertTrue(library.play(discover[0].mediaId))

        assertEquals(listOf("recent r2 of 2", "local Road trip p2", "service Discover sv1 of 2"), notes.played)
    }

    @Test
    fun `the queue shows the song playing and autoplay's, and picking one jumps there`() = runTest {
        val queue = library.children(CarLibrary.QUEUE)!!
        assertEquals(4, queue.size)
        assertTrue(queue[1].mediaMetadata.subtitle.toString().startsWith("Playing now"))
        assertEquals("Queue", queue[0].mediaMetadata.extras!!.getString(CarLibrary.GROUP_TITLE))
        assertEquals("Autoplay · Related", queue[3].mediaMetadata.extras!!.getString(CarLibrary.GROUP_TITLE))
        assertTrue(library.play(queue[2].mediaId))
        assertTrue(library.play(queue[3].mediaId))
        assertEquals(listOf("jump 2", "suggestion 0"), notes.played)
        // A place the queue no longer has plays nothing.
        assertFalse(library.play("queue/9"))
    }

    @Test
    fun `radio lists stations, played on their own`() = runTest {
        val stations = library.children(CarLibrary.RADIO)!!
        assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, stations[0].mediaMetadata.mediaType)
        assertTrue(library.play(stations[1].mediaId))
        assertEquals(listOf("station fm2"), notes.played)
    }

    @Test
    fun `search keeps what it found to pick from, and voice plays the first`() = runTest {
        val found = library.search("daft punk")
        assertEquals(2, found.size)
        assertEquals(found.map { it.mediaId }, library.searchResults().map { it.mediaId })
        assertTrue(library.play(found[1].mediaId))
        assertNotNull(library.playAsked("around the world"))
        assertEquals(listOf("search x2 of 2", "search x1 of 2"), notes.played)
    }

    @Test
    fun `play with nothing asked picks up the queue where it was`() = runTest {
        val resume = library.resumption()!!
        assertEquals("queue/1", resume.mediaId)
        assertEquals(42_000L, library.resumptionPositionMs())
        assertEquals(listOf(resume.mediaId), library.children(CarLibrary.RESUME)!!.map { it.mediaId })
        library.playAsked("")
        assertEquals(listOf("jump 1"), notes.played)

        // An empty queue: Home's first row instead.
        notes.queueState = QueueState()
        assertNull(library.resumption())
        library.playAsked("")
        assertEquals("home h1 of 3", notes.played.last())
    }

    @Test
    fun `every item can be asked about by its id`() = runTest {
        assertEquals("Home", library.item(CarLibrary.HOME)!!.mediaMetadata.title.toString())
        assertEquals("Recently played", library.item(CarLibrary.RECENT)!!.mediaMetadata.title.toString())
        val road = library.children(CarLibrary.LIBRARY)!![1]
        assertEquals("Road trip", library.item(road.mediaId)!!.mediaMetadata.title.toString())
        assertEquals("Chill", library.item("home/1")!!.mediaMetadata.title.toString())
        assertEquals("Song h3", library.item("home/0/2")!!.mediaMetadata.title.toString())
        assertNull(library.item("home/0/99"))
        assertNull(library.item("nowhere/1"))
        assertNull(library.children("nowhere"))
    }

    @Test
    fun `covers are served only for addresses the menu listed`() {
        val uri = CarArtwork.uriFor("app.test.carartwork", "https://example.com/cover.jpg")!!
        assertEquals("https://example.com/cover.jpg", CarArtwork.address(uri.lastPathSegment!!))
        assertNull(CarArtwork.address(CarArtwork.tokenOf("https://evil.example/whatever")))
        assertNull(CarArtwork.uriFor("app.test.carartwork", "file:///data/secret"))
        assertNull(CarArtwork.uriFor("app.test.carartwork", null))
    }

    @Test
    fun `the car's search lists artists above the songs, and an artist opens to their songs`() = runTest {
        notes.artists = listOf(Playlist("artist-named:Daft Punk", "Daft Punk", ProviderType.YOUTUBE_MUSIC))
        val found = library.search("daft punk")
        assertEquals(listOf("Daft Punk", "Song x1", "Song x2"), found.map { it.mediaMetadata.title.toString() })
        val artist = found.first()
        assertEquals(true, artist.mediaMetadata.isBrowsable)
        assertEquals("Artists", artist.mediaMetadata.extras?.getString(CarLibrary.GROUP_TITLE))
        val songs = library.children(artist.mediaId)!!
        assertEquals(listOf("Song sv1", "Song sv2"), songs.map { it.mediaMetadata.title.toString() })
        assertTrue(library.play(songs[1].mediaId))
        assertEquals(listOf("service Daft Punk sv2 of 2"), notes.played)
        assertEquals("Daft Punk", library.item(artist.mediaId)?.mediaMetadata?.title.toString())
        // A voice's "play ..." plays a song, never opens an artist.
        notes.played.clear()
        library.playAsked("daft punk")
        assertEquals(listOf("search x1 of 2"), notes.played)
    }

    @Test
    fun `the queue opens with the playing song's artist`() = runTest {
        notes.hasArtistPages = true
        val queue = library.children(CarLibrary.QUEUE)!!
        assertEquals("More by Artist q2", queue.first().mediaMetadata.title.toString())
        val songs = library.children(queue.first().mediaId)!!
        assertTrue(library.play(songs[0].mediaId))
        assertEquals(listOf("service Artist q2 sv1 of 2"), notes.played)
    }
}
