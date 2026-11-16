package app.noctorium.auto

import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.QueueState
import app.noctorium.playlists.LocalPlaylist

fun testSong(id: String, provider: ProviderType = ProviderType.YOUTUBE_MUSIC) = Track(
    provider = provider,
    id = id,
    title = "Song $id",
    artists = listOf(Artist("a$id", "Artist $id", provider)),
    durationMs = 200_000,
    artworkUrl = "https://example.com/$id.jpg",
    sourceUrl = "https://example.com/$id",
)

/** A Noctorium that only keeps notes of what it was asked to play. */
class NotedMusic : CarMusic {
    val played = mutableListOf<String>()
    var shelves = listOf(CarShelf("Fresh", "On YouTube Music", listOf(testSong("h1"), testSong("h2"), testSong("h3"))), CarShelf("Chill", null, listOf(testSong("c1"))))
    var recentSongs = listOf(testSong("r1"), testSong("r2"))
    var local = listOf(LocalPlaylist(id = "p/1 with spaces", title = "Road trip", tracks = listOf(testSong("p1"), testSong("p2"))))
    var service = listOf(Playlist(id = "PL9", title = "Discover", provider = ProviderType.SOUNDCLOUD))
    var queueState = QueueState(tracks = listOf(testSong("q1"), testSong("q2"), testSong("q3")), currentIndex = 1, suggestions = listOf(testSong("s1")), suggestionsFrom = "Related")
    var stationList = listOf(testSong("fm1", ProviderType.RADIO), testSong("fm2", ProviderType.RADIO))
    var readPlaylists = 0
    var loaded = false

    override fun homeShelves() = shelves
    override fun recent() = recentSongs
    override fun localPlaylists() = local
    override fun servicePlaylists() = service
    override suspend fun playlistTracks(playlist: Playlist): List<Track> {
        readPlaylists++
        return listOf(testSong("sv1", ProviderType.SOUNDCLOUD), testSong("sv2", ProviderType.SOUNDCLOUD))
    }
    override fun queue() = queueState
    override suspend fun search(text: String) = if (text.isBlank()) emptyList() else listOf(testSong("x1"), testSong("x2"))
    override suspend fun stations() = stationList
    override fun positionMs() = 42_000L
    override fun isLoaded() = loaded
    override fun playFromHome(track: Track, shelf: List<Track>) { played += "home ${track.id} of ${shelf.size}" }
    override fun playFromSearch(track: Track, results: List<Track>) { played += "search ${track.id} of ${results.size}" }
    override fun playRecent(track: Track, recent: List<Track>) { played += "recent ${track.id} of ${recent.size}" }
    override fun playLocalPlaylist(playlist: LocalPlaylist, startAt: Track) { played += "local ${playlist.title} ${startAt.id}" }
    override fun playPlaylist(playlist: Playlist, startAt: Track) { played += "service ${playlist.title} ${startAt.id} of ${playlist.tracks.size}" }
    override fun playStation(station: Track) { played += "station ${station.id}" }
    override fun jumpToQueueItem(index: Int) { played += "jump $index" }
    override fun playSuggestion(index: Int) { played += "suggestion $index" }
}
