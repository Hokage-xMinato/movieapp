package com.smarterz.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
// Philosophy: treat the WebView exactly like a browser iframe on a website.
//
// RESOURCES  (shouldInterceptRequest)  — allow everything, no exceptions.
//   Iframes inside vidsrc can load fonts, HLS segments, subtitles, player JS,
//   thumbnails from any CDN on any domain. We never touch resources.
//
// TOP-LEVEL NAVIGATION  (shouldOverrideUrlLoading)  — minimal blocking.
//   Only hard-block non-http schemes (intent://, market://) that would escape
//   the WebView and open another app. All http/https navigation is allowed so
//   the vidsrc → cloudnestra → sub-player redirect chain completes naturally.
//
// POPUPS  (onCreateWindow in SmartChromeClient)  — strict allowlist.
//   window.open / target=_blank are the only true "external" surface. Only
//   vidsrc domains and cloudnestra.com may open a popup; everything else is
//   silently dropped. Allowed popups are routed back into the same WebView.

class SmartWebViewClient(
    private val onPageReady: () -> Unit,
    private val onError: ((String) -> Unit)? = null
) : WebViewClient() {

    // Only block schemes that would escape the WebView and launch another app.
    // Do NOT add http/https hosts here — that breaks the player redirect chain.
    private val ESCAPE_SCHEMES = setOf(
        "intent", "android-app", "market", "tel", "sms",
        "mailto", "whatsapp", "tg", "viber", "fb", "twitter"
    )

    // JS injected into every frame to ensure player controls are fully visible,
    // bypass basic webdriver checks, and block ad overlays from exiting fullscreen.
    private val SPOOF_JS = """
        (function() {
            if (window.__smarterz_patched) return;
            window.__smarterz_patched = true;

            // ── 1. Hide webdriver fingerprint ──────────────────────────────────
            try {
                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
            } catch(e) {}

            // ── 2. Fix viewport meta ───────────────────────────────────────────
            try {
                var existing = document.querySelector('meta[name=viewport]');
                var content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                if (existing) {
                    existing.setAttribute('content', content);
                } else {
                    var m = document.createElement('meta');
                    m.name = 'viewport';
                    m.content = content;
                    (document.head || document.documentElement).appendChild(m);
                }
            } catch(e) {}

            // ── 3. Freeze all fullscreen-exit APIs ─────────────────────────────
            // Ad scripts call these to collapse the fullscreen view when their
            // invisible overlay link is tapped.
            try {
                var noop = function() { return Promise.resolve(); };
                Object.defineProperty(document, 'exitFullscreen',         { get: function() { return noop; }, configurable: true });
                Object.defineProperty(document, 'webkitExitFullscreen',   { get: function() { return noop; }, configurable: true });
                Object.defineProperty(document, 'webkitCancelFullScreen', { get: function() { return noop; }, configurable: true });
                Object.defineProperty(document, 'mozCancelFullScreen',    { get: function() { return noop; }, configurable: true });
                Object.defineProperty(document, 'msExitFullscreen',       { get: function() { return noop; }, configurable: true });
            } catch(e) {}

            // ── 4. Block ad overlay anchor clicks at capture phase ─────────────
            // Ad overlays are typically: a full-viewport <a> or <div> with a
            // z-index above the video, no visible text, positioned absolute/fixed.
            // We intercept every click in the capture phase (fires before the
            // element's own handler). If the target or its ancestor is an <a>
            // that looks like an ad overlay, we stop the event dead — preventing
            // both the navigation AND any side-effect that would trigger a
            // native fullscreen exit in the Android WebView.
            try {
                function isAdOverlayAnchor(el) {
                    // Walk up max 5 levels to find an <a> tag
                    var node = el;
                    for (var i = 0; i < 5; i++) {
                        if (!node || node === document.body) break;
                        if (node.tagName === 'A') {
                            var href = (node.getAttribute('href') || '').trim();
                            var style = window.getComputedStyle(node);
                            var pos = style.position;
                            // An ad overlay anchor has an external href, covers a
                            // large area, and is positioned absolutely/fixed.
                            if (href && href !== '#' && !href.startsWith('javascript') &&
                                (pos === 'absolute' || pos === 'fixed') &&
                                node.offsetWidth > 100 && node.offsetHeight > 100) {
                                return true;
                            }
                            // Also block any <a> whose only child is the video/player
                            // container (common ad trick: wrap player in a link).
                            if (href && href !== '#' && !href.startsWith('javascript') &&
                                node.children.length <= 1) {
                                var rect = node.getBoundingClientRect();
                                if (rect.width > window.innerWidth * 0.5 &&
                                    rect.height > window.innerHeight * 0.5) {
                                    return true;
                                }
                            }
                        }
                        node = node.parentElement;
                    }
                    return false;
                }

                document.addEventListener('click', function(e) {
                    if (isAdOverlayAnchor(e.target)) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                    }
                }, true);  // true = capture phase, fires before any element handler

                // Also intercept touchend at capture phase (some overlays use touch)
                document.addEventListener('touchend', function(e) {
                    if (isAdOverlayAnchor(e.target)) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                    }
                }, true);
            } catch(e) {}

            // ── 5. Neutralise window.location / window.open ad redirects ──────
            // Some ad scripts bypass <a> clicks and call these directly.
            try {
                var _open = window.open;
                window.open = function(url, name, features) {
                    // Only allow opens that look like player sub-frames
                    if (url && (url.indexOf('vidsrc') !== -1 || url.indexOf('cloudnestra') !== -1)) {
                        return _open.call(window, url, name, features);
                    }
                    // Block everything else silently
                    return { closed: true, close: function(){} };
                };
            } catch(e) {}
        })();
    """.trimIndent()
    // ── Resources: allow everything ──────────────────────────────────────────
    // shouldInterceptRequest fires for every sub-resource inside every iframe.
    // Returning null means "let the WebView handle it normally" — which is
    // exactly what we want. CDN segments, subtitles, poster images, player JS,
    // ad tracker pixels — all pass through. We are NOT an ad blocker at the
    // resource level; our only job is keeping the video stream intact.
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? = null  // never intercept — let everything load

    // ── Top-level navigation: block app-escape schemes + vidsrcme.ru root ───
    // shouldOverrideUrlLoading fires when the TOP-LEVEL WebView frame would
    // navigate. It does NOT fire for iframe src changes or resource loads.
    // We block:
    //   1. Non-http schemes that would escape to another app (intent, market, etc.)
    //   2. https://vidsrcme.ru/ with no meaningful path (the main website) —
    //      but NOT embed paths like /embed/movie/... or /embed/tv/... which must load.
    // All other http/https URLs are allowed so the vidsrc redirect chain completes.

    private fun isBlockedRootSite(url: android.net.Uri): Boolean {
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = url.host?.lowercase()?.removePrefix("www.") ?: return false
        // Only block the vidsrcme.ru main website (root or homepage, no embed path)
        if (host == "vidsrcme.ru") {
            val path = url.path?.trimEnd('/') ?: ""
            // Block root, empty path, or paths that don't start with /embed
            return !path.startsWith("/embed")
        }
        return false
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return true
        val scheme = url.scheme?.lowercase() ?: ""
        // Hard-block any scheme that would escape to another app
        if (scheme in ESCAPE_SCHEMES) return true
        // data: and blob: are internal — always allow
        if (scheme == "data" || scheme == "blob") return false
        // Block vidsrcme.ru root site (non-embed navigation)
        if (isBlockedRootSite(url)) return true
        // http/https: allow — the player redirect chain needs this
        if (scheme == "http" || scheme == "https") return false
        // Unknown scheme: block to be safe
        return true
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return true
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return false
        val parsed = try { Uri.parse(url) } catch (e: Exception) { return true }
        val scheme = parsed.scheme?.lowercase() ?: return true
        if (scheme in ESCAPE_SCHEMES) return true
        if (isBlockedRootSite(parsed)) return true
        if (scheme == "http" || scheme == "https") return false
        return true
    }

    // ── Page lifecycle ────────────────────────────────────────────────────────
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.evaluateJavascript(SPOOF_JS, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(SPOOF_JS, null)
        // Only fire onPageReady for the main frame, not sub-frame completions
        if (url != null && url != "about:blank" && view?.url == url) {
            onPageReady()
        }
    }

    // ── Errors: only surface fatal main-frame failures ────────────────────────
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val code = error?.errorCode ?: -1
            val desc = error?.description?.toString() ?: "Unknown error"
            // -2 = net::ERR_FAILED (common during redirect chain) — don't surface
            if (code != -2) onError?.invoke("Player error ($code): $desc")
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(
        view: WebView?, errorCode: Int, description: String?, failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        if (errorCode != -2) onError?.invoke("Player error ($errorCode): ${description ?: "Unknown error"}")
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // Only 5xx on the main frame is a real failure worth showing
        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) {
            onError?.invoke("Player failed to load (HTTP ${errorResponse?.statusCode}). Please try again.")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?) = true
}

class SmartChromeClient(
    private val fullscreenContainer: FrameLayout,
    private val onFullscreenEnter: () -> Unit,
    private val onFullscreenExit: () -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    // When true, the next onHideCustomView call is intentional (user action or
    // back-press) and should actually exit fullscreen. When false, any
    // onHideCustomView triggered by an ad click is immediately re-entered.
    var allowFullscreenExit: Boolean = false

    // Strict allowlist for window.open / target=_blank popups.
    // These are the ONLY domains allowed to open a popup that routes back into
    // the main WebView. Everything else (ad popunders, click-redirectors, etc.)
    // is silently dropped by returning false from onCreateWindow.
    // Note: vidsrcme.ru is intentionally NOT listed here — the root site is blocked.
    // The embed paths (/embed/...) are loaded directly, not via popups.
    private val POPUP_ALLOWED_HOSTS = setOf(
        "vidsrc.me", "vidsrc.to", "vidsrc.xyz",
        "vidsrc.net", "vidsrc.in", "vidsrc.pm", "vidsrc.rip",
        "cloudnestra.com"
    )

    // ── Fullscreen ────────────────────────────────────────────────────────────
    // When the player requests fullscreen, we attach the custom video surface
    // directly to the window's decor view (the true root of the window, above
    // all Activity layouts). This guarantees it covers 100% of the screen —
    // no app chrome, no title bar, no player controls bleed through.
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            // Already in fullscreen — dismiss existing first
            onHideCustomView()
            return
        }
        customView = view ?: return
        customViewCallback = callback

        // Add directly to the window decor view so it sits above everything
        fullscreenContainer.addView(
            customView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        onFullscreenEnter()
    }

    override fun onHideCustomView() {
        if (!allowFullscreenExit && customView != null) {
            // This exit was NOT triggered by a deliberate user action — it came
            // from an ad overlay click. DO NOT touch the view hierarchy at all:
            // removing and re-adding a SurfaceView/TextureView kills its surface
            // and causes a permanent black screen that requires a full restart.
            // Just re-lock the system UI flags and orientation — everything else
            // stays exactly as it is, so the video surface is never disturbed.
            onFullscreenEnter()
            return
        }

        fullscreenContainer.removeView(customView)
        fullscreenContainer.visibility = View.GONE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        allowFullscreenExit = false   // reset for next time
        onFullscreenExit()
    }

    fun isFullscreen() = customView != null

    fun exitFullscreenIfNeeded(): Boolean {
        return if (customView != null) {
            allowFullscreenExit = true   // this IS an intentional exit
            onHideCustomView()
            true
        } else false
    }

    // ── Popup windows (window.open / target=_blank) ───────────────────────────
    // This is the ONE place we enforce a strict allowlist.
    // How it works:
    //   1. Create a temporary invisible WebView to receive the popup.
    //   2. That WebView's client intercepts the first navigation request.
    //   3. If the destination host is in POPUP_ALLOWED_HOSTS, load it in the
    //      MAIN WebView (so it plays inside the player, not a new window).
    //   4. Anything else is silently dropped — the temp WebView is discarded.
    //   5. The site never knows the popup was handled differently; it just
    //      sees a successful window.open() return value.
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        val mainView = view ?: return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

        // Temp WebView — strictly invisible and unfocusable so it doesn't steal 
        // Android window focus and accidentally dismiss the fullscreen CustomView.
        val tempWebView = WebView(mainView.context).apply {
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
        }
        
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val host = req?.url?.host?.lowercase()?.removePrefix("www.") ?: return true
                if (POPUP_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    // Route allowed popup back into the main player WebView
                    mainView.loadUrl(req.url.toString())
                }
                // Always return true: consume the navigation, never open external browser
                return true
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                if (url == null) return true
                val host = try {
                    Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return true
                } catch (e: Exception) { return true }
                if (POPUP_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    mainView.loadUrl(url)
                }
                return true
            }
        }

        transport.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }

    // ── JS dialogs: suppress all ──────────────────────────────────────────────
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?):
        Boolean { result?.cancel(); return true }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?):
        Boolean { result?.cancel(); return true }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?,
        defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }

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
        // Move fullscreenContainer out of the Activity layout and into the
        // window's decor view root. This makes it a true overlay that sits
        // above ALL Activity content — nothing bleeds through when fullscreen.
        val decor = window.decorView as FrameLayout
        (fullscreenContainer.parent as? ViewGroup)?.removeView(fullscreenContainer)
        decor.addView(
            fullscreenContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.GONE
        fullscreenContainer.setBackgroundColor(Color.BLACK)

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
            fullscreenContainer = fullscreenContainer,
            onFullscreenEnter = {
                // Reset the exit guard — the next exit must be explicitly allowed.
                chromeClient.allowFullscreenExit = false
                // Lock to landscape and hide all system UI.
                // SCREEN_ORIENTATION_SENSOR_LANDSCAPE allows both landscape
                // directions but refuses portrait — so ad-click-induced orientation
                // resets cannot flip the device back to portrait.
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
                    ctrl.hide(WindowInsetsCompat.Type.systemBars())
                    ctrl.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                // Hide the entire playerModal so the app chrome (title, controls)
                // disappears — only the fullscreenContainer (attached to decor root)
                // is visible, covering 100% of the window.
                playerModal.visibility = View.INVISIBLE
            },
            onFullscreenExit = {
                // Restore portrait, system UI, and player modal
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                playerModal.visibility = View.VISIBLE
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
        
        // Enable these to allow the WebView and iframes to respect mobile dimensions 
        // and meta viewport tags properly.
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100  

        // Use a standard modern Android Mobile User-Agent.
        // This solves the Cloudnestra infinite loading loop by matching the UA 
        // to the actual mobile hardware footprint.
        s.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        
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

        closePlayer.setOnClickListener {
            // Mark as intentional so onHideCustomView doesn't fight the close
            if (::chromeClient.isInitialized) chromeClient.allowFullscreenExit = true
            closePlayer()
        }

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
        // Always mark as intentional before closing so the fullscreen guard
        // doesn't try to re-enter fullscreen during teardown.
        if (::chromeClient.isInitialized) chromeClient.allowFullscreenExit = true
        // If we're in fullscreen, collapse it first so orientation resets cleanly.
        if (::chromeClient.isInitialized) chromeClient.exitFullscreenIfNeeded()
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

    // Re-lock orientation to landscape whenever a configuration change fires
    // while the video is in fullscreen mode. This catches the race where an ad
    // overlay click triggers an orientation reset before the JS guard or the
    // onHideCustomView guard can suppress it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::chromeClient.isInitialized && chromeClient.isFullscreen()) {
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // Re-lock to landscape — stay in fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            // Re-apply immersive flags in case the system UI reappeared
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        playerWebView.destroy()
    }
}
