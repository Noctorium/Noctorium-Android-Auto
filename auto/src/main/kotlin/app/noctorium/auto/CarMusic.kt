package app.noctorium.auto

import app.noctorium.core.AppState
import app.noctorium.domain.Playlist
import app.noctorium.domain.Track
import app.noctorium.playback.QueueState
import app.noctorium.playlists.LocalPlaylist

/** A row of Home, as the car lists it. */
data class CarShelf(val title: String, val subtitle: String?, val tracks: List<Track>)

/**
 * Noctorium, as the car sees it: what its menu is made of, and what picking something there does.
 *
 * The app's [AppState] is the one answer to this ([AppStateCarMusic]); the tests give the menu one of their own. Every
 * read is of the state as it is now, and every play goes through the same calls the phone's own screens make, so the
 * car and the phone are never two players and never disagree.
 */
interface CarMusic {
    fun homeShelves(): List<CarShelf>
    fun recent(): List<Track>
    fun localPlaylists(): List<LocalPlaylist>

    /** Playlists on the services the listener is signed in to; their songs are read on asking. */
    fun servicePlaylists(): List<Playlist>
    suspend fun playlistTracks(playlist: Playlist): List<Track>
    fun queue(): QueueState
    suspend fun search(text: String): List<Track>

    /** Artists for [text], as pages whose songs [playlistTracks] reads: what the car's search lists above the songs. */
    suspend fun searchArtists(text: String): List<Playlist> = emptyList()

    /** The page of [track]'s artist on its service, or null for none: the queue's "More by" folder. */
    fun artistPage(track: Track): Playlist? = null

    /** Radio stations: the most listened-to in the country chosen in the app. */
    suspend fun stations(): List<Track>

    /** Where the song playing is, for picking it up again from the car. */
    fun positionMs(): Long

    /** Whether a song is loaded in the player, or on its way there: then there is nothing to pick up again. */
    fun isLoaded(): Boolean

    fun playFromHome(track: Track, shelf: List<Track>)
    fun playFromSearch(track: Track, results: List<Track>)
    fun playRecent(track: Track, recent: List<Track>)
    fun playLocalPlaylist(playlist: LocalPlaylist, startAt: Track)
    fun playPlaylist(playlist: Playlist, startAt: Track)
    fun playStation(station: Track)
    fun jumpToQueueItem(index: Int)
    fun playSuggestion(index: Int)
}

/** The car's view of the app's own state. */
class AppStateCarMusic(private val state: AppState) : CarMusic {
    override fun homeShelves(): List<CarShelf> {
        val ui = state.ui.value
        val pinned = if (ui.pinnedTracks.isEmpty()) emptyList() else listOf(CarShelf("Pinned", null, ui.pinnedTracks))
        val recent = if (ui.recentTracks.isEmpty()) emptyList() else listOf(CarShelf("Jump back in", "What you played last", ui.recentTracks))
        // Rows of playlists and albums open somewhere on the phone; the car lists rows of songs.
        val suggested = ui.homeSections.filter { it.tracks.isNotEmpty() }.map { CarShelf(it.title, it.subtitle, it.tracks) }
        return pinned + recent + suggested
    }

    override fun recent(): List<Track> = state.ui.value.recentTracks

    override fun localPlaylists(): List<LocalPlaylist> = state.library.value.localPlaylists

    override fun servicePlaylists(): List<Playlist> = state.library.value.playlists

    override suspend fun playlistTracks(playlist: Playlist): List<Track> =
        playlist.tracks.ifEmpty { state.playlistTracks(playlist) }

    override fun queue(): QueueState = state.shownQueue.value

    override suspend fun search(text: String): List<Track> = state.searchQuietly(text)

    override suspend fun searchArtists(text: String): List<Playlist> = state.searchArtistsQuietly(text)

    override fun artistPage(track: Track): Playlist? = track.artists.firstOrNull()?.let { state.artistPageQuietly(it, track) }

    override suspend fun stations(): List<Track> = state.radioStationsQuietly()

    override fun positionMs(): Long = state.playback.value.positionMs

    override fun isLoaded(): Boolean = state.playback.value.track != null

    override fun playFromHome(track: Track, shelf: List<Track>) = state.playFromHome(track, shelf)

    override fun playFromSearch(track: Track, results: List<Track>) = state.playFromSearch(track, results)

    override fun playRecent(track: Track, recent: List<Track>) =
        state.play(track, app.noctorium.domain.PlaybackOrigin.LIBRARY, recent)

    override fun playLocalPlaylist(playlist: LocalPlaylist, startAt: Track) = state.playLocalPlaylist(playlist, startAt)

    override fun playPlaylist(playlist: Playlist, startAt: Track) = state.playPlaylist(playlist, startAt)

    override fun playStation(station: Track) = state.playStation(station)

    override fun jumpToQueueItem(index: Int) = state.jumpToQueueItem(index)

    override fun playSuggestion(index: Int) = state.playSuggestion(index)
}
