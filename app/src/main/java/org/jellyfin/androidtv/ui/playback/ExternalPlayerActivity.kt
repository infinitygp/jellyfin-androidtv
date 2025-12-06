package org.jellyfin.androidtv.ui.playback

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Activity that, once opened, opens the first item of the [VideoQueueManager.getCurrentVideoQueue] list in an external media player app.
 * Once returned it will notify the server of item completion.
 */
class ExternalPlayerActivity : FragmentActivity() {
	companion object {
		const val EXTRA_POSITION = "position"

		// https://mx.j2inter.com/api
		private const val API_MX_TITLE = "title"
		private const val API_MX_SEEK_POSITION = "position"
		private const val API_MX_FILENAME = "filename"
		private const val API_MX_SECURE_URI = "secure_uri"
		private const val API_MX_RETURN_RESULT = "return_result"
		private const val API_MX_RESULT_ID = "com.mxtech.intent.result.VIEW"
		private const val API_MX_RESULT_POSITION = "position"
		private const val API_MX_RESULT_END_BY = "end_by"
		private const val API_MX_RESULT_END_BY_PLAYBACK_COMPLETION = "playback_completion"
		private const val API_MX_SUBS = "subs"
		private const val API_MX_SUBS_NAME = "subs.name"
		private const val API_MX_SUBS_FILENAME = "subs.filename"

		// https://wiki.videolan.org/Android_Player_Intents/
		private const val API_VLC_SUBTITLES = "subtitles_location"
		private const val API_VLC_RESULT_ID = "org.videolan.vlc.player.result"
		private const val API_VLC_RESULT_POSITION = "extra_position"

		// https://www.vimu.tv/player-api
		private const val API_VIMU_TITLE = "forcename"
		private const val API_VIMU_SEEK_POSITION = "startfrom"
		private const val API_VIMU_RESUME = "forceresume"
		private const val API_VIMU_RESULT_ID = "net.gtvbox.videoplayer.result"
		private const val API_VIMU_RESULT_PLAYBACK_COMPLETED = 1
		private const val API_VIMU_RESULT_ERROR = 4

		// The extra keys used by various video players to read the end position
		private val resultPositionExtras = arrayOf(API_MX_RESULT_POSITION, API_VLC_RESULT_POSITION)
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val api by inject<ApiClient>()

	private var playerStartTime: Instant? = null

	private val playVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val playerFinishedTime = Instant.now()
		Timber.i("Playback finished with result code ${result.resultCode}")
		videoQueueManager.setCurrentMediaPosition(videoQueueManager.getCurrentMediaPosition() + 1)

		if (result.isVimuError) {
			Toast.makeText(this, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			finish()
		} else {
			onItemFinished(result, playerFinishedTime)
		}
	}

	private val ActivityResult.isVimuError get() =
		data?.action == API_VIMU_RESULT_ID && resultCode == API_VIMU_RESULT_ERROR

	private var currentItem: Pair<BaseItemDto, MediaSourceInfo>? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val position = intent.getLongExtra(EXTRA_POSITION, 0).milliseconds
		playNext(position)
	}

	private fun playNext(position: Duration = Duration.ZERO) {
		val currentPosition = videoQueueManager.getCurrentMediaPosition()
		val item = videoQueueManager.getCurrentVideoQueue().getOrNull(currentPosition) ?: return finish()
		val mediaSource = item.mediaSources?.firstOrNull { it.id?.toUUIDOrNull() == item.id }

		if (mediaSource == null) {
			Toast.makeText(this, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
			finish()
		} else {
			playItem(item, mediaSource, position)
		}
	}

	private fun playItem(item: BaseItemDto, mediaSource: MediaSourceInfo, position: Duration) {
		val url = api.videosApi.getVideoStreamUrl(
			itemId = item.id,
			mediaSourceId = mediaSource.id,
			static = true,
		)

		val title = item.getDisplayName(this)
		val fileName = mediaSource.path?.let { File(it).name }
		val externalSubtitles = mediaSource.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE && it.isExternal }
			?.sortedWith(compareBy<MediaStream> { it.isDefault }.thenBy { it.index })
			.orEmpty()

		val subtitleUrls = externalSubtitles.map { mediaStream ->
			// We cannot use the DeliveryUrl as that is only populated when using the playback info API, which we skip as we'll always direct
			// play when using external players. We need to infer the subtitle format based on its path (similar to how the server
			// calculates it)
			val format = mediaStream.path?.substringAfterLast('.', missingDelimiterValue = mediaStream.codec.orEmpty()) ?: "srt"
			api.subtitleApi.getSubtitleUrl(
				routeItemId = item.id,
				routeMediaSourceId = mediaSource.id.toString(),
				routeIndex = mediaStream.index,
				routeFormat = format,
			)
		}.toTypedArray()
		val subtitleNames = externalSubtitles.map { it.displayTitle ?: it.title.orEmpty() }.toTypedArray()
		val subtitleLanguages = externalSubtitles.map { it.language.orEmpty() }.toTypedArray()

		Timber.i(
			"Starting item ${item.id} from $position with ${subtitleUrls.size} external subtitles: $url${
				subtitleUrls.joinToString(", ", ", ")
			}"
		)

		val playIntent = Intent(Intent.ACTION_VIEW).apply {
			val mediaType = when (item.mediaType) {
				MediaType.VIDEO -> "video/*"
				MediaType.AUDIO -> "audio/*"
				else -> null
			}

			setDataAndTypeAndNormalize(url.toUri(), mediaType)

			putExtra(API_MX_SEEK_POSITION, position.inWholeMilliseconds.toInt())
			putExtra(API_MX_RETURN_RESULT, true)
			putExtra(API_MX_TITLE, title)
			putExtra(API_MX_FILENAME, fileName)
			putExtra(API_MX_SECURE_URI, true)
			putExtra(API_MX_SUBS, subtitleUrls)
			putExtra(API_MX_SUBS_NAME, subtitleNames)
			putExtra(API_MX_SUBS_FILENAME, subtitleLanguages)

			if (subtitleUrls.isNotEmpty()) putExtra(API_VLC_SUBTITLES, subtitleUrls.first().toString())

			putExtra(API_VIMU_SEEK_POSITION, position.inWholeMilliseconds.toInt())
			putExtra(API_VIMU_RESUME, false)
			putExtra(API_VIMU_TITLE, title)
		}

		try {
			currentItem = item to mediaSource
			playerStartTime = Instant.now()
			playVideoLauncher.launch(playIntent)
		} catch (_: ActivityNotFoundException) {
			Toast.makeText(this, R.string.no_player_message, Toast.LENGTH_LONG).show()
			finish()
		}
	}


	private fun onItemFinished(result: ActivityResult, playerFinishedTime: Instant) {
		if (currentItem == null) {
			Toast.makeText(this@ExternalPlayerActivity, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		val (item, mediaSource) = currentItem!!
		val extras = result.data?.extras ?: Bundle.EMPTY
		val resultData = result.data

		val runtime = (mediaSource.runTimeTicks ?: item.runTimeTicks)?.ticks

		val startTime = playerStartTime
		val playbackDuration = if (startTime != null) {
			java.time.Duration.between(startTime, playerFinishedTime).toMillis().milliseconds
		} else null

		if (playbackDuration != null && playbackDuration < 1.seconds) {
			Timber.i("Playback took less than a second - assuming it failed")
			Toast.makeText(this@ExternalPlayerActivity, R.string.no_player_message, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		var endPosition = resultPositionExtras.firstNotNullOfOrNull { key ->
			@Suppress("DEPRECATION") val value = extras.get(key)
			if (value is Number) value.toLong().milliseconds
			else null
		}

		if (endPosition == null && resultData != null && runtime != null) {
			when (resultData.action) {
				API_MX_RESULT_ID -> {
					if (result.resultCode == RESULT_OK &&
						resultData.getStringExtra(API_MX_RESULT_END_BY) == API_MX_RESULT_END_BY_PLAYBACK_COMPLETION) {
						endPosition = runtime
						Timber.i("Detected playback completion for MX player.")
					}
				}
				API_VLC_RESULT_ID -> {
					if (result.resultCode == RESULT_OK) {
						endPosition = runtime
						Timber.i("Detected playback completion for VLC player.")
					}
				}
			}

			if (result.resultCode == API_VIMU_RESULT_PLAYBACK_COMPLETED) {
				endPosition = runtime
				Timber.i("Detected playback completion for Vimu player.")
			}
		}

		val shouldPlayNext = runtime != null && endPosition != null && endPosition >= (runtime * 0.9)

		val playbackCompletedByTime = runtime != null && playbackDuration != null &&
			endPosition == null && playbackDuration >= (runtime * 0.9)

		if (playbackCompletedByTime) {
			Timber.i("Player returned no position, but playback duration ($playbackDuration) suggests completion (runtime: $runtime)")
		}

		val playbackWasShort = runtime != null && playbackDuration != null &&
			playbackDuration < (runtime * 0.9) && endPosition == null

		if (playbackWasShort) {
			showMarkWatchedDialog(item, mediaSource, playbackDuration)
		} else {
			val reportPosition = endPosition ?: if (playbackCompletedByTime) runtime else playbackDuration
			reportAndContinue(item, mediaSource, reportPosition, shouldPlayNext || playbackCompletedByTime)
		}
	}

	private fun showMarkWatchedDialog(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		playbackDuration: Duration?
	) {
		AlertDialog.Builder(this)
			.setTitle(R.string.mark_watched)
			.setMessage(R.string.mark_watched_message)
			.setPositiveButton(R.string.lbl_yes) { _, _ ->
				val runtime = (mediaSource.runTimeTicks ?: item.runTimeTicks)?.ticks
				reportAndContinue(item, mediaSource, runtime, shouldPlayNext = true)
			}
			.setNegativeButton(R.string.lbl_no) { _, _ ->
				reportAndContinue(item, mediaSource, playbackDuration, shouldPlayNext = false)
			}
			.setCancelable(false)
			.show()
	}

	private fun reportAndContinue(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		endPosition: Duration?,
		shouldPlayNext: Boolean
	) {
		lifecycleScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					api.playStateApi.reportPlaybackStopped(
						PlaybackStopInfo(
							itemId = item.id,
							mediaSourceId = mediaSource.id,
							positionTicks = endPosition?.inWholeTicks,
							failed = false,
						)
					)
				}
			}.onFailure { error ->
				Timber.w(error, "Failed to report playback stop event")
			}

			dataRefreshService.lastPlayback = Instant.now()
			when (item.type) {
				BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
				BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
				else -> Unit
			}

			if (shouldPlayNext) playNext()
			else finish()
		}
	}
}
