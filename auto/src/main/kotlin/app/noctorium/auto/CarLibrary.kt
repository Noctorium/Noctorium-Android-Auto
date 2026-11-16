package app.noctorium.auto

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.artworkAt
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The menu Android Auto shows for Noctorium, and what picking something in it does.
 *
 * Four tabs, as many as a car shows: Home, with its rows; Library, with Recently played and the playlists; the queue,
 * with what autoplay lined up after it; and Radio. Search is the car's own search box and its voice, see [search].
 *
 * Every item has an id that says where it is: `home/2/5` is the sixth song of Home's third row, `local/<id>/0` the
 * first song of a playlist on this phone. Picking one ([play]) reads its list again and plays it with the same call the
 * phone's own screen makes for that list -- Home's song with Home's rules for what plays after it, a playlist's from
 * where it was picked -- so the car never builds a queue of its own.
 */
class CarLibrary(
    private val music: CarMusic,
    /** A cover's address as Android Auto can open it, see [CarArtwork]; null where there is none. */
    private val artwork: (String?) -> Uri? = { null },
) {
    /** What the car's search box or voice found last: its results are picked from by their place. */
    @Volatile
    private var lastSearch: List<Track> = emptyList()

    /** When something was last picked, see [resume]. */
    @Volatile
    private var pickedAt = 0L

    /** The id of what was last picked, said back while its song is still on its way, see [resume]. */
    @Volatile
    private var pickedId: String? = null

    /** Radio stations as last listed, picked from by their place. */
    @Volatile
    private var lastStations: List<Track> = emptyList()

    /** Service playlists' songs as last read, by playlist, so a row picked plays what was shown. */
    private val servicePlaylistTracks = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    fun root(): MediaItem = folder(ROOT, "Noctorium", null, browsableStyle = GRID)

    /** The root a car asks for as it starts, to offer what was playing: its one child is [resumption]. */
    fun recentRoot(): MediaItem = folder(RESUME, "Noctorium", null)

    /** What the last search found, as the car lists it. */
    fun searchResults(): List<MediaItem> = songs(SEARCH, lastSearch)

    /** What is under [parentId], or null for an id the menu has no such place for. */
    suspend fun children(parentId: String): List<MediaItem>? {
        val path = parse(parentId)
        return when (path.firstOrNull()) {
            ROOT -> listOf(
                folder(HOME, "Home", null, browsableStyle = GRID),
                folder(LIBRARY, "Library", null),
                folder(QUEUE, "Queue", null),
                folder(RADIO, "Radio", null, playableStyle = GRID),
            )
            HOME -> when (path.size) {
                1 -> music.homeShelves().mapIndexed { i, shelf ->
                    folder(id(HOME, i), shelf.title, shelf.subtitle, cover = shelf.tracks.firstOrNull()?.artworkUrl, playableStyle = LIST)
                }
                else -> tracksOf(parentId)?.let { songs(parentId, it) }
            }
            LIBRARY -> buildList {
                val recent = music.recent()
                if (recent.isNotEmpty()) add(folder(RECENT, "Recently played", count(recent.size), cover = recent.first().artworkUrl))
                music.localPlaylists().forEach { playlist ->
                    add(folder(id(LOCAL, playlist.id), playlist.title, count(playlist.tracks.size), cover = playlist.tracks.firstOrNull()?.artworkUrl))
                }
                music.servicePlaylists().forEach { playlist ->
                    add(folder(id(SERVICE, serviceKey(playlist)), playlist.title, playlist.provider.displayName, cover = playlist.artworkUrl ?: playlist.tracks.firstOrNull()?.artworkUrl))
                }
            }
            QUEUE -> {
                val queue = music.queue()
                queue.tracks.mapIndexed { i, track -> song(id(QUEUE, i), track, group = "Queue", current = i == queue.currentIndex) } +
                    queue.suggestions.mapIndexed { i, track -> song(id(NEXT, i), track, group = queue.suggestionsFrom?.let { "Autoplay · $it" } ?: "Autoplay") }
            }
            RADIO -> {
                lastStations = runCatching { music.stations() }.getOrDefault(lastStations)
                lastStations.mapIndexed { i, station -> song(id(RADIO, i), station) }
            }
            RECENT, LOCAL, SERVICE, SEARCH -> tracksOf(parentId)?.let { songs(parentId, it) }
            RESUME -> listOfNotNull(resumption())
            else -> null
        }
    }

    /** One item by its id, for a car that asks about something it was shown. */
    suspend fun item(id: String): MediaItem? {
        val path = parse(id)
        // The folders, found where they are listed: the tabs under the root, Recently played and the playlists under
        // Library, the rows under Home.
        val listedIn = when {
            id == ROOT -> return root()
            id == RECENT -> LIBRARY
            path.size == 1 -> ROOT
            path.size == 2 && path.first() in setOf(LOCAL, SERVICE) -> LIBRARY
            path.size == 2 && path.first() == HOME -> HOME
            else -> null
        }
        if (listedIn != null) return children(listedIn)?.firstOrNull { it.mediaId == id }
        // A song: the list it is in, at its place.
        val index = path.last().toIntOrNull() ?: return null
        val parent = parentOf(id) ?: return null
        return tracksOf(parent)?.getOrNull(index)?.let { song(id, it) }
    }

    /** Searches, and keeps what was found for the car to pick from. */
    suspend fun search(query: String): List<MediaItem> {
        lastSearch = runCatching { music.search(query) }.getOrDefault(emptyList())
        return songs(SEARCH, lastSearch)
    }

    /**
     * Plays what was picked. True if [mediaId] was one of the menu's songs and it is now playing; false for anything
     * else, which the player is left to deal with as it would.
     */
    suspend fun play(mediaId: String): Boolean {
        val path = parse(mediaId)
        val index = path.lastOrNull()?.toIntOrNull() ?: return false
        val parent = parentOf(mediaId) ?: return false
        val section = path.first()
        if (section == QUEUE) {
            if (index !in music.queue().tracks.indices) return false
            music.jumpToQueueItem(index)
            picked(mediaId)
            return true
        }
        if (section == NEXT) {
            if (index !in music.queue().suggestions.indices) return false
            music.playSuggestion(index)
            picked(mediaId)
            return true
        }
        val tracks = tracksOf(parent) ?: return false
        val track = tracks.getOrNull(index) ?: return false
        when (section) {
            HOME -> music.playFromHome(track, tracks)
            SEARCH -> music.playFromSearch(track, tracks)
            RECENT -> music.playRecent(track, tracks)
            RADIO -> music.playStation(track)
            LOCAL -> music.localPlaylists().firstOrNull { it.id == path[1] }?.let { music.playLocalPlaylist(it, track) } ?: return false
            SERVICE -> music.servicePlaylists().firstOrNull { serviceKey(it) == path[1] }?.let { music.playPlaylist(it.copy(tracks = tracks), track) } ?: return false
            else -> return false
        }
        picked(mediaId)
        return true
    }

    private fun picked(mediaId: String) {
        pickedAt = System.currentTimeMillis()
        pickedId = mediaId
    }

    /**
     * What a voice asked for -- "play Daft Punk on Noctorium" -- played: the first song found, with the rest after it as
     * a search's songs are. Nothing asked for is "play some music": the queue as it was, or else Home's first row.
     */
    suspend fun playAsked(query: String): MediaItem? {
        if (query.isBlank()) {
            resumption()?.let { item -> if (play(item.mediaId)) return item }
            val shelf = music.homeShelves().firstOrNull() ?: return null
            val first = shelf.tracks.firstOrNull() ?: return null
            music.playFromHome(first, shelf.tracks)
            return song(id(HOME, 0, 0), first)
        }
        val found = search(query).firstOrNull() ?: return null
        return if (play(found.mediaId)) found else null
    }

    /** The song to pick up from when the car's play is pressed with nothing loaded: the queue's, where it was. */
    fun resumption(): MediaItem? {
        val queue = music.queue()
        val track = queue.tracks.getOrNull(queue.currentIndex) ?: return null
        return song(id(QUEUE, queue.currentIndex), track)
    }

    fun resumptionPositionMs(): Long = music.positionMs()

    /**
     * The car's play with an empty player behind it. Media3 asks this both when nothing is loaded -- a car starting up,
     * the app just opened -- and right after a pick, whose song is still being looked up and so not in the player yet.
     * Only the first picks the queue up again; the second, or a song already loaded, is left as it is, and the player's
     * own play carries on with it. Either way the song is what is said back.
     */
    suspend fun resume(): MediaItem? {
        val justPicked = System.currentTimeMillis() - pickedAt < PICK_SETTLES_MS
        if (justPicked) pickedId?.let { id -> item(id) }?.let { return it }
        val item = resumption() ?: return null
        if (!justPicked && !music.isLoaded()) {
            music.jumpToQueueItem(music.queue().currentIndex)
            picked(item.mediaId)
        }
        return item
    }

    // ---- the lists behind the ids --------------------------------------------------------------------

    /** The songs under a list's id, as they are now; null for an id that is not a list of songs. */
    private suspend fun tracksOf(parentId: String): List<Track>? {
        val path = parse(parentId)
        return when (path.firstOrNull()) {
            HOME -> path.getOrNull(1)?.toIntOrNull()?.let { music.homeShelves().getOrNull(it)?.tracks }
            RECENT -> music.recent()
            LOCAL -> music.localPlaylists().firstOrNull { it.id == path.getOrNull(1) }?.tracks
            SERVICE -> {
                val key = path.getOrNull(1) ?: return null
                val playlist = music.servicePlaylists().firstOrNull { serviceKey(it) == key } ?: return null
                servicePlaylistTracks[key] ?: runCatching { music.playlistTracks(playlist) }.getOrNull()?.also { servicePlaylistTracks[key] = it }
            }
            SEARCH -> lastSearch
            RADIO -> lastStations.ifEmpty { runCatching { music.stations() }.getOrDefault(emptyList()).also { lastStations = it } }
            QUEUE -> music.queue().tracks
            NEXT -> music.queue().suggestions
            else -> null
        }
    }

    private fun songs(parentId: String, tracks: List<Track>): List<MediaItem> =
        tracks.take(MAX_ITEMS).mapIndexed { i, track -> song("$parentId/$i", track) }

    private fun song(id: String, track: Track, group: String? = null, current: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            if (group != null) putString(GROUP_TITLE, group)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistLine)
            // The queue's own song says so, as the queue on the phone lifts it.
            .setSubtitle(if (current) "Playing now · ${track.artistLine}" else track.artistLine)
            .setAlbumTitle(track.album?.title)
            .setArtworkUri(artwork(track.artworkUrl?.let { artworkAt(it, COVER_PX) }))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(if (track.provider == ProviderType.RADIO) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun folder(
        id: String,
        title: String,
        subtitle: String?,
        cover: String? = null,
        browsableStyle: Int = LIST,
        playableStyle: Int = LIST,
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(CONTENT_STYLE_BROWSABLE, browsableStyle)
            putInt(CONTENT_STYLE_PLAYABLE, playableStyle)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artwork(cover?.let { artworkAt(it, COVER_PX) }))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    companion object {
        const val ROOT = "noctorium"
        const val HOME = "home"
        const val LIBRARY = "library"
        const val QUEUE = "queue"
        const val RADIO = "radio"
        const val RECENT = "recent"
        const val LOCAL = "local"
        const val SERVICE = "service"
        const val NEXT = "next"
        const val SEARCH = "search"
        const val RESUME = "resume"

        /** As many of a list as a car is given: Android Auto shows far fewer, and scrolls the rest. */
        const val MAX_ITEMS = 100

        /** How long after a pick the car's play is taken to be for the song picked, see [resume]. */
        private const val PICK_SETTLES_MS = 10_000L
        private const val COVER_PX = 320

        // Android Auto's own keys for how a folder's children are laid out, and for headings in a list.
        const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val LIST = 1
        const val GRID = 2

        private fun count(n: Int) = if (n == 1) "1 song" else "$n songs"

        /** A service playlist's key in an id: its service and its own id. */
        fun serviceKey(playlist: Playlist): String = "${playlist.provider.name}:${playlist.id}"

        /** An id from its parts, each made safe to sit between slashes. */
        fun id(vararg parts: Any): String = parts.joinToString("/") { URLEncoder.encode(it.toString(), "UTF-8") }

        fun parse(id: String): List<String> = id.split('/').map { URLDecoder.decode(it, "UTF-8") }

        /** The list a song's id is in: `home/2/5` is in `home/2`. Null for an id with no list above it. */
        fun parentOf(id: String): String? = id.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
    }
}
