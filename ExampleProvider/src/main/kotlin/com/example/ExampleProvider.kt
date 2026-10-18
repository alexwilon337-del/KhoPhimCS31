package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ============================================================================
// KHO PHIM 1: NGUONC.COM
// ============================================================================
class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/films/phim-moi-cap-nhat?page=" to "Phim Mới Cập Nhật",
        "$mainUrl/api/films/danh-sach/phim-le?page=" to "Phim Lẻ",
        "$mainUrl/api/films/danh-sach/phim-bo?page=" to "Phim Bộ",
        "$mainUrl/api/films/danh-sach/hoat-hinh?page=" to "Hoạt Hình"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data + page).parsedSafe<ListResponse>()
        val home = response?.items?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    private fun FilmItem.toSearchResult(): SearchResponse? {
        val title = this.name ?: return null
        val slug = this.slug ?: return null
        val poster = this.thumbUrl ?: this.posterUrl

        return newAnimeSearchResponse(title, "$mainUrl/api/film/$slug", TvType.TvSeries) {
            this.posterUrl = poster
            addSub(this.currentEpisode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/films/search?keyword=$encodedQuery"
        val response = app.get(url).parsedSafe<ListResponse>()
        return response?.items?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url).parsedSafe<DetailResponse>()?.movie ?: return null

        val title = response.name ?: return null
        val poster = response.posterUrl ?: response.thumbUrl
        val description = response.description?.replace(Regex("<.*?>"), "")

        val episodeMap = mutableMapOf<String, MutableList<EpisodeData>>()

        response.episodes?.forEach { server ->
            val serverName = server.serverName ?: "Server"
            server.items?.forEach { item ->
                val epName = item.name ?: "Full"
                val epList = episodeMap.getOrPut(epName) { mutableListOf() }
                epList.add(
                    EpisodeData(
                        serverName = serverName,
                        m3u8 = item.m3u8,
                        embed = item.embed
                    )
                )
            }
        }

        val episodes = episodeMap.entries.mapIndexed { index, entry ->
            val epName = entry.key
            val dataJson = entry.value.toJson()

            newEpisode(dataJson) {
                this.name = if (epName.lowercase().contains("tập")) epName else "Tập $epName"
                this.episode = epName.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
            }
        }

        val isMovie = response.totalEpisodes == 1 && episodes.size == 1

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<List<EpisodeData>>(data)

        for (source in sources) {
            val m3u8Url = source.m3u8
            if (!m3u8Url.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${source.serverName}",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            } else if (!source.embed.isNullOrBlank()) {
                loadExtractor(source.embed, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    data class ListResponse(@JsonProperty("items") val items: List<FilmItem>? = null)
    data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("current_episode") val currentEpisode: String? = null
    )
    data class DetailResponse(@JsonProperty("movie") val movie: MovieDetail? = null)
    data class MovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("episodes") val episodes: List<ServerGroup>? = null
    )
    data class ServerGroup(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<EpisodeItem>? = null
    )
    data class EpisodeItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
    data class EpisodeData(
        @JsonProperty("serverName") val serverName: String,
        @JsonProperty("m3u8") val m3u8: String?,
        @JsonProperty("embed") val embed: String?
    )
}

// ============================================================================
// KHO PHIM 2: YANHH3D.CHAT
// ============================================================================
class YanHH3DProvider : MainAPI() {
    override var mainUrl = "https://yanhh3d.chat"
    override var name = "YanHH3D"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/moi-cap-nhat?page=" to "Mới Cập Nhật",
        "$mainUrl/the-loai/tien-hiep?page=" to "Tiên Hiệp",
        "$mainUrl/the-loai/huyen-huyen?page=" to "Huyền Huyễn",
        "$mainUrl/the-loai/xuyen-khong?page=" to "Xuyên Không"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val items = document.select("article, .halim-item, .movie-item, .film-item, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.contains("/the-loai/") || href.contains("/moi-cap-nhat")) return null

        val title = this.selectFirst(".entry-title, .title, .film-name, h2, h3")?.text()?.trim()
            ?: linkElement.attr("title").trim()
            .ifBlank { linkElement.text().trim() }
        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifBlank { null }
                ?: imgElement?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgElement?.attr("src")
        )

        val epText = this.selectFirst(".episode, .status, .halim-episode, .ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/tim-kiem?keyword=$encodedQuery").document
        return document.select("article, .halim-item, .movie-item, .film-item, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .entry-title, .film-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".movie-poster img, .poster img, .halim-movie-poster img")?.attr("src")
        )

        val description = document.selectFirst(".entry-content, .film-description, .desc, meta[property=og:description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()

        var epElements = document.select(".halim-list-eps a, #list-episodes a, .list-episode a, .episodes a")

        if (epElements.isEmpty()) {
            val watchUrl = document.selectFirst("a:contains(Xem Thuyết Minh), a:contains(Xem Vietsub), a:contains(Xem Phim), a.btn-watch")
                ?.attr("href")?.let { fixUrlNull(it) }
            if (watchUrl != null && watchUrl != url) {
                val watchDoc = app.get(watchUrl).document
                epElements = watchDoc.select(".halim-list-eps a, #list-episodes a, .list-episode a, .episodes a")
            }
        }

        val episodes = epElements.mapNotNull { ep ->
            val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
            val epName = ep.text().trim().ifBlank { "Full" }
            newEpisode(epHref) {
                this.name = if (epName.lowercase().contains("tập")) epName else "Tập $epName"
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text
        val document = response.document

        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val m3u8List = m3u8Regex.findAll(html).map { it.value.replace("\\/", "/") }.distinct().toList()
        for (m3u8Url in m3u8List) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - Direct M3U8",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
        }

        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val iframeSrc = fixUrlNull(iframe.attr("src"))
            if (!iframeSrc.isNullOrBlank()) {
                loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}
