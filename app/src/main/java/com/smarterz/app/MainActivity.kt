package com.smarterz.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

// ─── Data Models ─────────────────────────────────────────────────────────────

data class MediaItem(
    val id: Int,
    val type: String,
    val title: String,
    val detail: String,
    val poster: String?,
    val season: Int = 1,
    val episode: Int = 1
)

data class TmdbSearchResponse(
    val results: List<TmdbSearchResult>?,
    @SerializedName("total_pages") val totalPages: Int = 1
)

data class TmdbSearchResult(
    val id: Int,
    val title: String?,
    val name: String?,
    @SerializedName("media_type") val mediaType: String,
    @SerializedName("poster_path") val posterPath: String?
) {
    val displayTitle get() = title ?: name ?: "Unknown"
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w300$it" }
}

data class TmdbMovieDetail(
    val id: Int,
    val title: String?,
    @SerializedName("poster_path") val posterPath: String?,
    val overview: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    val genres: List<TmdbGenre>?,
    val runtime: Int?
) {
    val displayTitle get() = title ?: "Unknown"
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w400$it" }
    val year get() = releaseDate?.take(4) ?: ""
}

data class TmdbTvDetail(
    val id: Int,
    val name: String?,
    @SerializedName("poster_path") val posterPath: String?,
    val overview: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    val genres: List<TmdbGenre>?,
    val seasons: List<TmdbSeason>?
) {
    val displayTitle get() = name ?: "Unknown"
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w400$it" }
    val year get() = firstAirDate?.take(4) ?: ""
}

data class TmdbGenre(val id: Int, val name: String)

data class TmdbSeason(
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("episode_count") val episodeCount: Int,
    val name: String?
)

// ─── Storage ──────────────────────────────────────────────────────────────────

class RecentStorage(context: Context) {
    private val prefs = context.getSharedPreferences("moviesfan_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val KEY = "recent_v5"

    fun getAll(): List<MediaItem> = try {
        val type = object : TypeToken<List<MediaItem>>() {}.type
        gson.fromJson(prefs.getString(KEY, "[]"), type) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun add(item: MediaItem) {
        val list = getAll().filter { !(it.id == item.id && it.type == item.type) }.toMutableList()
        list.add(0, item)
        if (list.size > 20) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun remove(id: Int, type: String) {
        prefs.edit().putString(KEY, gson.toJson(
            getAll().filter { !(it.id == id && it.type == type) }
        )).apply()
    }
}

// ─── API ──────────────────────────────────────────────────────────────────────

class TmdbApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val BASE = "https://proxy-api-server-woz1.onrender.com/v1/tmdb/3"
    private val KEY = "374ed57246cdd0d51e7f9c7eb9e682f0"

    fun search(query: String, page: Int = 1): TmdbSearchResponse? =
        fetch("$BASE/search/multi?api_key=$KEY&query=${enc(query)}&page=$page") {
            gson.fromJson(it, TmdbSearchResponse::class.java)
        }

    fun movie(id: Int): TmdbMovieDetail? =
        fetch("$BASE/movie/$id?api_key=$KEY&append_to_response=genres") {
            gson.fromJson(it, TmdbMovieDetail::class.java)
        }

    fun tv(id: Int): TmdbTvDetail? =
        fetch("$BASE/tv/$id?api_key=$KEY&append_to_response=genres") {
            gson.fromJson(it, TmdbTvDetail::class.java)
        }

    private fun <T> fetch(url: String, parse: (String) -> T): T? = try {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (resp.isSuccessful) resp.body?.string()?.let { parse(it) } else null
    } catch (e: Exception) { null }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

// ─── WebViewClient ─────────────────────────────────────────────────────────────
// Strategy: we load a local HTML wrapper page (base URL = https://vidsrc.me/)
// which contains an <iframe> pointing at the real embed URL.
// The top frame is our wrapper — it never navigates away.
// shouldOverrideUrlLoading blocks ALL top-frame navigation to anything except
// our wrapper's own origin and the known player/CDN hosts.
// Resource requests (scripts, media, XHR) are allowed freely so the player works.

class SmartWebViewClient(
    private val onPageReady: () -> Unit,
    private val onError: ((String) -> Unit)? = null
) : WebViewClient() {

    // Hosts whose pages are allowed to load in the top frame (our wrapper + player domains)
    private val PLAYER_HOSTS = setOf(
        "vidsrc.me", "vidsrcme.ru", "vidsrc.to", "vidsrc.xyz",
        "vidsrc.net", "vidsrc.in", "vidsrc.pm", "vidsrc.rip",
        "cloudnestra.com", "multiembed.mov", "moviesapi.club",
        "embedme.top", "vid2fcdn.xyz", "superembed.stream"
    )

    private val BLOCKED_SCHEMES = setOf(
        "intent", "android-app", "market", "tel", "sms",
        "mailto", "whatsapp", "tg", "javascript"
    )

    // JS injected into EVERY page/frame to kill ad tricks and spoof browser fingerprint
    private val SPOOF_JS = """
        (function() {
            // ── Kill all dialog / popup / navigation tricks ──────────────────
            window.alert   = function() {};
            window.confirm = function() { return false; };
            window.prompt  = function() { return null; };
            window.open    = function() { return null; };
            // Block location redirects from ad scripts
            var _href = window.location.href;
            try {
                Object.defineProperty(window, 'location', {
                    get: function() { return window._safeLocation || location; },
                    set: function(v) { /* swallow */ }
                });
            } catch(e) {}
            // ── Spoof navigator so vidsrc thinks we are a real browser ───────
            try {
                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                Object.defineProperty(navigator, 'platform',  { get: function() { return 'Win32'; } });
                Object.defineProperty(navigator, 'vendor',    { get: function() { return 'Google Inc.'; } });
                Object.defineProperty(navigator, 'appVersion',{ get: function() {
                    return '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
                }});
                Object.defineProperty(navigator, 'userAgent', { get: function() {
                    return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
                }});
                // Remove Android/mobile UA clues
                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
            } catch(e) {}
            // ── Spoof screen / window sizes (desktop) ────────────────────────
            try {
                Object.defineProperty(screen, 'width',       { get: function() { return 1920; } });
                Object.defineProperty(screen, 'height',      { get: function() { return 1080; } });
                Object.defineProperty(screen, 'availWidth',  { get: function() { return 1920; } });
                Object.defineProperty(screen, 'availHeight', { get: function() { return 1040; } });
                Object.defineProperty(window, 'outerWidth',  { get: function() { return 1920; } });
                Object.defineProperty(window, 'outerHeight', { get: function() { return 1040; } });
            } catch(e) {}
            // ── Hide WebView-specific properties ─────────────────────────────
            try { delete window._phantom; } catch(e) {}
            try { delete window.callPhantom; } catch(e) {}
            // ── Make document.referrer look like we came from a normal site ──
            try {
                Object.defineProperty(document, 'referrer', {
                    get: function() { return 'https://www.google.com/'; }
                });
            } catch(e) {}
        })();
    """.trimIndent()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val scheme = request?.url?.scheme?.lowercase() ?: ""
        // Block non-http/data/blob schemes at the resource level entirely
        if (scheme !in listOf("https", "http", "data", "blob", "")) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        return null // allow all http(s) resources through — player needs CDN, subtitles, etc.
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return true
        val scheme = url.scheme?.lowercase() ?: ""
        // Kill all non-http schemes (intent://, market://, etc.)
        if (scheme in BLOCKED_SCHEMES || scheme !in listOf("http", "https")) return true
        val host = url.host?.lowercase()?.removePrefix("www.") ?: return true
        // Allow navigation only to known player hosts; block everything else (ads)
        return !PLAYER_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return true
        if (url.startsWith("about:") || url.startsWith("data:")) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return true }
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme in BLOCKED_SCHEMES || scheme !in listOf("http", "https")) return true
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return true
        return !PLAYER_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject spoofing JS as early as possible into every frame
        view?.evaluateJavascript(SPOOF_JS, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Re-inject after page fully loads (some scripts overwrite navigator late)
        view?.evaluateJavascript(SPOOF_JS, null)
        onPageReady()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // Only report errors for the main frame (not sub-resources like ads/trackers)
        if (request?.isForMainFrame == true) {
            val desc = error?.description?.toString() ?: "Unknown error"
            val code = error?.errorCode ?: -1
            onError?.invoke("Player error ($code): $desc")
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(
        view: WebView?, errorCode: Int, description: String?, failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        onError?.invoke("Player error ($errorCode): ${description ?: "Unknown error"}")
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            val code = errorResponse?.statusCode ?: 0
            onError?.invoke("Player failed to load (HTTP $code). Please try again.")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?) = true
}

class SmartChromeClient(
    private val playerHosts: Set<String>,
    private val fullscreenContainer: FrameLayout,
    private val onFullscreenEnter: () -> Unit,
    private val onFullscreenExit: () -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    // Called when the video player requests fullscreen (user taps fullscreen button)
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        customView = view ?: return
        customViewCallback = callback
        fullscreenContainer.addView(customView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        fullscreenContainer.visibility = View.VISIBLE
        onFullscreenEnter()
    }

    // Called when fullscreen is dismissed (user taps back/exit in fullscreen player)
    override fun onHideCustomView() {
        fullscreenContainer.removeView(customView)
        fullscreenContainer.visibility = View.GONE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        onFullscreenExit()
    }

    fun isFullscreen() = customView != null

    fun exitFullscreenIfNeeded(): Boolean {
        return if (customView != null) {
            onHideCustomView()
            true
        } else false
    }

    // vidsrc sometimes opens sub-players via window.open — we allow those
    // but route them back through the same WebView with the same restrictions.
    // Any popup to an unknown host is silently dropped.
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean,
        isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        val newWebView = WebView(view!!.context)
        newWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val host = req?.url?.host?.lowercase()?.removePrefix("www.") ?: return true
                // Only route allowed hosts back into the main WebView
                if (playerHosts.any { host == it || host.endsWith(".$it") }) {
                    view.loadUrl(req.url.toString())
                }
                return true // always consume — never open external browser
            }
        }
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?):
        Boolean { result?.cancel(); return true }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?):
        Boolean { result?.cancel(); return true }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?,
        defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }

    // Suppress "Changes you made may not be saved" dialog when changing episodes
    override fun onJsBeforeUnload(
        view: WebView?, url: String?, message: String?, result: JsResult?
    ): Boolean { result?.cancel(); return true }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class MediaAdapter(
    private var items: List<MediaItem>,
    private val showRemove: Boolean,
    private val onClick: (MediaItem) -> Unit,
    private val onRemove: ((MediaItem) -> Unit)? = null
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.cardPoster)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val detail: TextView = v.findViewById(R.id.cardDetail)
        val typeBadge: TextView = v.findViewById(R.id.cardTypeBadge)
        val removeBtn: ImageButton = v.findViewById(R.id.removeBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.title.text = item.title
        // In recently-watched (showRemove=true) show progress; in search results show generic label
        h.detail.text = if (showRemove && item.type == "tv" && item.season > 0) {
            "S${item.season} · E${item.episode}"
        } else {
            item.detail
        }
        h.typeBadge.text = if (item.type == "tv") "TV" else "MOVIE"
        h.typeBadge.setBackgroundColor(
            if (item.type == "tv") Color.parseColor("#1a6fd4") else Color.parseColor("#c0392b")
        )
        h.removeBtn.visibility = if (showRemove) View.VISIBLE else View.GONE
        if (!item.poster.isNullOrEmpty()) {
            Glide.with(h.poster.context)
                .load(item.poster)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.poster_placeholder)
                .error(R.drawable.poster_placeholder)
                .centerCrop()
                .into(h.poster)
        } else {
            h.poster.setImageResource(R.drawable.poster_placeholder)
        }
        h.itemView.setOnClickListener { onClick(item) }
        h.removeBtn.setOnClickListener { onRemove?.invoke(item) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}

// ─── MainActivity ─────────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var homeBtn: ImageButton
    private lateinit var searchInput: EditText
    private lateinit var searchBtn: ImageButton
    private lateinit var homeSection: ScrollView
    private lateinit var recentRecycler: RecyclerView
    private lateinit var recentEmpty: TextView
    private lateinit var searchSection: LinearLayout
    private lateinit var searchLoading: ProgressBar
    private lateinit var searchRecycler: RecyclerView
    private lateinit var paginationRow: LinearLayout
    private lateinit var prevPageBtn: Button
    private lateinit var nextPageBtn: Button
    private lateinit var pageIndicator: TextView
    private lateinit var detailSection: ScrollView
    private lateinit var detailLoading: ProgressBar
    private lateinit var detailContent: LinearLayout
    private lateinit var detailPoster: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailYear: TextView
    private lateinit var detailGenre: TextView
    private lateinit var detailRating: TextView
    private lateinit var detailBadgeType: TextView
    private lateinit var detailOverview: TextView
    private lateinit var playButton: Button
    private lateinit var playerModal: LinearLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var closePlayer: ImageButton
    private lateinit var playerWebView: WebView
    private lateinit var playerLoadingOverlay: FrameLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerEpisodeInfo: TextView
    private lateinit var tvControls: LinearLayout
    private lateinit var movieControls: LinearLayout
    private lateinit var seasonSpinner: Spinner
    private lateinit var episodeSpinner: Spinner
    private lateinit var prevEpBtn: ImageButton
    private lateinit var nextEpBtn: ImageButton
    private lateinit var chromeClient: SmartChromeClient

    // State
    private val api = TmdbApi()
    private lateinit var storage: RecentStorage
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var recentAdapter: MediaAdapter
    private lateinit var searchAdapter: MediaAdapter
    private var seasonsMap = mutableMapOf<Int, TmdbSeason>()
    private var currentId = 0
    private var currentType = "tv"
    private var currentSeason = 1
    private var currentEpisode = 1
    private var searchPage = 1
    private var totalPages = 1
    private var lastQuery = ""
    private var currentPosterUrl: String? = null

    companion object {
        const val EMBED_TV = "https://vidsrcme.ru/embed/tv"
        const val EMBED_MOVIE = "https://vidsrcme.ru/embed/movie"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storage = RecentStorage(this)
        bindViews()
        setupWebView()
        setupAdapters()
        setupListeners()
        showHome()
    }

    private fun bindViews() {
        homeBtn = findViewById(R.id.homeBtn)
        searchInput = findViewById(R.id.searchInput)
        searchBtn = findViewById(R.id.searchButton)
        homeSection = findViewById(R.id.homeSection)
        recentRecycler = findViewById(R.id.recentRecycler)
        recentEmpty = findViewById(R.id.recentEmpty)
        searchSection = findViewById(R.id.searchSection)
        searchLoading = findViewById(R.id.searchLoading)
        searchRecycler = findViewById(R.id.searchRecycler)
        paginationRow = findViewById(R.id.paginationRow)
        prevPageBtn = findViewById(R.id.prevPageBtn)
        nextPageBtn = findViewById(R.id.nextPageBtn)
        pageIndicator = findViewById(R.id.pageIndicator)
        detailSection = findViewById(R.id.detailSection)
        detailLoading = findViewById(R.id.detailLoading)
        detailContent = findViewById(R.id.detailContent)
        detailPoster = findViewById(R.id.detailPoster)
        detailTitle = findViewById(R.id.detailTitle)
        detailYear = findViewById(R.id.detailYear)
        detailGenre = findViewById(R.id.detailGenre)
        detailRating = findViewById(R.id.detailRating)
        detailBadgeType = findViewById(R.id.detailBadgeType)
        detailOverview = findViewById(R.id.detailOverview)
        playButton = findViewById(R.id.playButton)
        playerModal = findViewById(R.id.playerModal)
        videoContainer = findViewById(R.id.videoContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        closePlayer = findViewById(R.id.closePlayer)
        playerWebView = findViewById(R.id.playerWebView)
        playerLoadingOverlay = findViewById(R.id.playerLoadingOverlay)
        playerTitle = findViewById(R.id.playerTitle)
        playerEpisodeInfo = findViewById(R.id.playerEpisodeInfo)
        tvControls = findViewById(R.id.tvControls)
        movieControls = findViewById(R.id.movieControls)
        seasonSpinner = findViewById(R.id.seasonSpinner)
        episodeSpinner = findViewById(R.id.episodeSpinner)
        prevEpBtn = findViewById(R.id.prevEpisodeBtn)
        nextEpBtn = findViewById(R.id.nextEpisodeBtn)

        // Set videoContainer to 16:9 based on screen width
        val screenWidth = resources.displayMetrics.widthPixels
        val videoHeight = screenWidth * 9 / 16
        videoContainer.layoutParams = videoContainer.layoutParams.apply {
            height = videoHeight
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val playerHosts = setOf(
            "vidsrc.me", "vidsrcme.ru", "vidsrc.to", "vidsrc.xyz",
            "vidsrc.net", "vidsrc.in", "vidsrc.pm", "vidsrc.rip",
            "cloudnestra.com", "multiembed.mov", "moviesapi.club",
            "embedme.top", "vid2fcdn.xyz", "superembed.stream"
        )
        playerWebView.webViewClient = SmartWebViewClient(
            onPageReady = { playerLoadingOverlay.visibility = View.GONE },
            onError = { message ->
                runOnUiThread {
                    playerLoadingOverlay.visibility = View.GONE
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Playback Error")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
        playerWebView.webChromeClient = SmartChromeClient(
            playerHosts = playerHosts,
            fullscreenContainer = fullscreenContainer,
            onFullscreenEnter = {
                // Go true fullscreen: landscape, hide system UI
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
                    ctrl.hide(WindowInsetsCompat.Type.systemBars())
                    ctrl.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                // Expand fullscreenContainer to cover the whole window
                fullscreenContainer.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            },
            onFullscreenExit = {
                // Restore portrait and system UI
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        ).also { chromeClient = it }
        val s = playerWebView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = false
        s.useWideViewPort = false
        // Full desktop Chrome 124 UA — no "Android", no "Mobile", no "wv" (WebView marker)
        // This is the single most important thing to make vidsrc not redirect to google.com
        s.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(playerWebView, true)
        }
        playerWebView.setBackgroundColor(Color.BLACK)
        // Silently swallow any download attempts (ad download tricks)
        playerWebView.setDownloadListener { _, _, _, _, _ -> }
    }

    private fun setupAdapters() {
        recentAdapter = MediaAdapter(
            emptyList(), true,
            onClick = { loadContent(it.id, it.type, it.season, it.episode) },
            onRemove = { storage.remove(it.id, it.type); renderRecent() }
        )
        recentRecycler.layoutManager = GridLayoutManager(this, 3)
        recentRecycler.adapter = recentAdapter

        searchAdapter = MediaAdapter(
            emptyList(), false,
            onClick = { loadContent(it.id, it.type, 1, 1) }
        )
        searchRecycler.layoutManager = GridLayoutManager(this, 3)
        searchRecycler.adapter = searchAdapter
    }

    private fun setupListeners() {
        homeBtn.setOnClickListener { searchInput.setText(""); showHome() }

        searchBtn.setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) { hideKeyboard(); doSearch(q) }
        }

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) { hideKeyboard(); doSearch(q) }
                true
            } else false
        }

        prevPageBtn.setOnClickListener {
            if (searchPage > 1) doSearch(lastQuery, searchPage - 1)
        }
        nextPageBtn.setOnClickListener {
            if (searchPage < totalPages) doSearch(lastQuery, searchPage + 1)
        }

        closePlayer.setOnClickListener { closePlayer() }

        prevEpBtn.setOnClickListener {
            if (currentEpisode > 1) {
                currentEpisode--
            } else {
                val keys = seasonsMap.keys.sorted()
                val idx = keys.indexOf(currentSeason)
                if (idx > 0) {
                    currentSeason = keys[idx - 1]
                    currentEpisode = seasonsMap[currentSeason]?.episodeCount ?: 1
                }
            }
            syncSpinnersToState()
            updateTvFrame()
        }

        nextEpBtn.setOnClickListener {
            val epCount = seasonsMap[currentSeason]?.episodeCount ?: 1
            if (currentEpisode < epCount) {
                currentEpisode++
            } else {
                val keys = seasonsMap.keys.sorted()
                val idx = keys.indexOf(currentSeason)
                if (idx < keys.size - 1) {
                    currentSeason = keys[idx + 1]
                    currentEpisode = 1
                }
            }
            syncSpinnersToState()
            updateTvFrame()
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private fun showHome() {
        homeSection.visibility = View.VISIBLE
        searchSection.visibility = View.GONE
        detailSection.visibility = View.GONE
        renderRecent()
    }

    private fun renderRecent() {
        val items = storage.getAll()
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recentRecycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        recentAdapter.update(items)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    private fun doSearch(query: String, page: Int = 1) {
        lastQuery = query; searchPage = page
        homeSection.visibility = View.GONE
        detailSection.visibility = View.GONE
        searchSection.visibility = View.VISIBLE
        searchLoading.visibility = View.VISIBLE
        searchRecycler.visibility = View.GONE
        paginationRow.visibility = View.GONE

        scope.launch {
            val result = withContext(Dispatchers.IO) { api.search(query, page) }
            searchLoading.visibility = View.GONE
            if (result == null) {
                Toast.makeText(this@MainActivity, "Search failed. Check connection.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            totalPages = result.totalPages
            val items = (result.results ?: emptyList())
                .filter { it.mediaType == "tv" || it.mediaType == "movie" }
                .map {
                    MediaItem(
                        it.id, it.mediaType, it.displayTitle,
                        if (it.mediaType == "tv") "TV Series" else "Movie",
                        it.posterUrl
                    )
                }
            searchAdapter.update(items)
            searchRecycler.visibility = View.VISIBLE
            if (totalPages > 1) {
                paginationRow.visibility = View.VISIBLE
                prevPageBtn.isEnabled = page > 1
                nextPageBtn.isEnabled = page < totalPages
                pageIndicator.text = "Page $page of $totalPages"
            }
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    private fun loadContent(id: Int, type: String, season: Int, episode: Int) {
        currentId = id; currentType = type
        currentSeason = season; currentEpisode = episode
        seasonsMap.clear()
        homeSection.visibility = View.GONE
        searchSection.visibility = View.GONE
        detailSection.visibility = View.VISIBLE
        detailLoading.visibility = View.VISIBLE
        detailContent.visibility = View.GONE

        scope.launch {
            if (type == "movie") {
                val d = withContext(Dispatchers.IO) { api.movie(id) }
                detailLoading.visibility = View.GONE
                if (d == null) {
                    Toast.makeText(this@MainActivity, "Failed to load details.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                currentPosterUrl = d.posterUrl
                detailContent.visibility = View.VISIBLE
                detailTitle.text = d.displayTitle
                detailYear.text = d.year
                detailGenre.text = d.genres?.take(3)?.joinToString(" · ") { it.name } ?: ""
                detailRating.text = "★ ${d.voteAverage?.let { "%.1f".format(it) } ?: "?"}"
                detailBadgeType.text = "MOVIE"
                detailBadgeType.setBackgroundColor(Color.parseColor("#c0392b"))
                detailOverview.text = d.overview ?: "No overview available."
                d.posterUrl?.let {
                    Glide.with(this@MainActivity).load(it)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .placeholder(R.drawable.poster_placeholder)
                        .error(R.drawable.poster_placeholder)
                        .into(detailPoster)
                }
                playButton.setOnClickListener { openPlayer("movie") }
            } else {
                val d = withContext(Dispatchers.IO) { api.tv(id) }
                detailLoading.visibility = View.GONE
                if (d == null) {
                    Toast.makeText(this@MainActivity, "Failed to load details.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                d.seasons?.forEach { s ->
                    if (s.seasonNumber > 0 && s.episodeCount > 0) seasonsMap[s.seasonNumber] = s
                }
                currentPosterUrl = d.posterUrl
                detailContent.visibility = View.VISIBLE
                detailTitle.text = d.displayTitle
                detailYear.text = d.year
                detailGenre.text = d.genres?.take(3)?.joinToString(" · ") { it.name } ?: ""
                detailRating.text = "★ ${d.voteAverage?.let { "%.1f".format(it) } ?: "?"}"
                detailBadgeType.text = "TV SERIES"
                detailBadgeType.setBackgroundColor(Color.parseColor("#1a6fd4"))
                detailOverview.text = d.overview ?: "No overview available."
                d.posterUrl?.let {
                    Glide.with(this@MainActivity).load(it)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .placeholder(R.drawable.poster_placeholder)
                        .error(R.drawable.poster_placeholder)
                        .into(detailPoster)
                }
                playButton.setOnClickListener { openPlayer("tv") }
            }
        }
    }

    // ─── Player ──────────────────────────────────────────────────────────────

    private fun openPlayer(type: String) {
        playerModal.visibility = View.VISIBLE
        playerLoadingOverlay.visibility = View.VISIBLE
        playerTitle.text = detailTitle.text
        if (type == "movie") {
            tvControls.visibility = View.GONE
            movieControls.visibility = View.VISIBLE
            playerEpisodeInfo.text = "Movie"
            loadPlayerFrame("$EMBED_MOVIE/$currentId")
            storage.add(
                MediaItem(currentId, "movie", detailTitle.text.toString(),
                    "Movie", currentPosterUrl)
            )
        } else {
            tvControls.visibility = View.VISIBLE
            movieControls.visibility = View.GONE
            buildSpinners()
            updateTvFrame()
        }
    }

    /**
     * Loads the embed URL directly in the WebView.
     * Ad redirects are blocked by SmartWebViewClient.shouldOverrideUrlLoading.
     */
    private fun loadPlayerFrame(embedUrl: String) {
        playerWebView.loadUrl(embedUrl)
    }

    private fun closePlayer() {
        playerModal.visibility = View.GONE
        playerLoadingOverlay.visibility = View.VISIBLE
        playerWebView.stopLoading()
        playerWebView.loadUrl("about:blank")
    }

    private fun buildSpinners() {
        val seasons = seasonsMap.values.sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) return

        val seasonLabels = seasons.map {
            it.name?.takeIf { n -> n != "Season ${it.seasonNumber}" }
                ?.let { n -> "$n (${it.episodeCount} eps)" }
                ?: "Season ${it.seasonNumber}  ·  ${it.episodeCount} eps"
        }

        val sAdapter = ArrayAdapter(this, R.layout.spinner_item, seasonLabels)
        sAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        seasonSpinner.adapter = sAdapter

        val sIdx = seasons.indexOfFirst { it.seasonNumber == currentSeason }.coerceAtLeast(0)
        seasonSpinner.setSelection(sIdx, false)

        seasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ns = seasons[pos].seasonNumber
                if (ns != currentSeason) {
                    currentSeason = ns
                    currentEpisode = 1
                    buildEpisodeSpinner()
                    updateTvFrame()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        buildEpisodeSpinner()
    }

    private fun buildEpisodeSpinner() {
        val epCount = seasonsMap[currentSeason]?.episodeCount ?: return
        val labels = (1..epCount).map { "Episode $it" }
        val eAdapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        eAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        episodeSpinner.adapter = eAdapter
        episodeSpinner.setSelection((currentEpisode - 1).coerceAtLeast(0), false)
        episodeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ne = pos + 1
                if (ne != currentEpisode) { currentEpisode = ne; updateTvFrame() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun syncSpinnersToState() {
        buildSpinners()
    }

    private fun updateTvFrame() {
        playerLoadingOverlay.visibility = View.VISIBLE
        loadPlayerFrame("$EMBED_TV/$currentId/$currentSeason/$currentEpisode")
        val epInfo = "S${currentSeason} · E${currentEpisode}"
        playerEpisodeInfo.text = epInfo
        storage.add(
            MediaItem(currentId, "tv", detailTitle.text.toString(),
                epInfo, currentPosterUrl, currentSeason, currentEpisode)
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            // If in fullscreen video, exit fullscreen first — don't close player
            playerModal.visibility == View.VISIBLE && ::chromeClient.isInitialized && chromeClient.exitFullscreenIfNeeded() -> { /* fullscreen exited */ }
            playerModal.visibility == View.VISIBLE -> closePlayer()
            detailSection.visibility == View.VISIBLE -> {
                if (lastQuery.isNotEmpty()) {
                    detailSection.visibility = View.GONE
                    searchSection.visibility = View.VISIBLE
                } else {
                    showHome()
                }
            }
            searchSection.visibility == View.VISIBLE -> {
                searchInput.setText("")
                showHome()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        playerWebView.destroy()
    }
}
