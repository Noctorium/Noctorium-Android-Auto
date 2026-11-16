package app.noctorium.auto

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Android Auto's way into Noctorium: the media library session's answers, from a [CarLibrary].
 *
 * The phone app builds its session with this: `MediaLibrarySession.Builder(service, player, CarLibraryCallback(...))`.
 * The car browses ([onGetChildren]), searches ([onSearch]), and plays what is picked ([onSetMediaItems]) -- which here
 * starts it through the app's queue, as the phone's own screens do, rather than handing the player a song of its own.
 * The player the session is built round is told the picked items too, by Media3; it lets them go by, since the queue
 * has already started them (see Noctorium-Mobile's QueueAwarePlayer).
 */
@OptIn(UnstableApi::class)
class CarLibraryCallback(
    private val library: CarLibrary,
    /** Where the answers are worked out: the main thread, as the app's state is read and changed there. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : MediaLibrarySession.Callback {

    constructor(context: Context, music: CarMusic) : this(
        CarLibrary(music) { url -> CarArtwork.uriFor(CarArtwork.authority(context), url) },
    )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        // Asked for what was playing last -- a car starting up, offering to pick up where it left off: a root whose one
        // child is that song.
        val root = if (params?.isRecent == true) library.recentRoot() else library.root()
        LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(rootExtras()).build())
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        library.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children = library.children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(pageOf(children, page, pageSize), params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val found = library.search(query)
        session.notifySearchResultChanged(browser, query, found.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        LibraryResult.ofItemList(pageOf(library.searchResults(), page, pageSize), params)
    }

    /**
     * Something picked in the car, or asked for by voice: played through the app's queue. What comes back is the song
     * as the car should show it until the player says what is playing.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val index = if (startIndex in mediaItems.indices) startIndex else 0
        val picked = mediaItems.getOrNull(index)
        val played = when {
            picked == null -> null
            picked.mediaId.isNotEmpty() && library.play(picked.mediaId) -> library.item(picked.mediaId) ?: picked
            // A voice's "play …": no item, only what was said.
            else -> library.playAsked(picked.requestMetadata.searchQuery.orEmpty())
        }
        MediaSession.MediaItemsWithStartPosition(listOfNotNull(played), 0, C.TIME_UNSET)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        // Only reached for a single item asked for without onSetMediaItems; it is played as a pick is.
        val first = mediaItems.firstOrNull()
        val played = when {
            first == null -> null
            first.mediaId.isNotEmpty() && library.play(first.mediaId) -> library.item(first.mediaId) ?: first
            else -> library.playAsked(first.requestMetadata.searchQuery.orEmpty())
        }
        listOfNotNull(played).toMutableList()
    }

    /** The car's play pressed with nothing loaded, or a car starting up: the queue's song, where it was. */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val item = library.resume()
        MediaSession.MediaItemsWithStartPosition(listOfNotNull(item), 0, if (item != null) library.resumptionPositionMs() else C.TIME_UNSET)
    }

    /** Stops the work under way, when the session goes. */
    fun release() = scope.cancel()

    private fun rootExtras() = Bundle().apply {
        putBoolean(CarLibrary.SEARCH_SUPPORTED, true)
        putInt(CarLibrary.CONTENT_STYLE_BROWSABLE, CarLibrary.LIST)
        putInt(CarLibrary.CONTENT_STYLE_PLAYABLE, CarLibrary.LIST)
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        return ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size)))
    }
}
