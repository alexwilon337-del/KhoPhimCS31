package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

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
        val items = response?.items ?: emptyList()
        val home = ArrayList<SearchResponse>()
        for (i in 0 until items.size) {
            val res = items[i].toSearchResult()
            if (res != null) {
                home.add(res)
            }
        }
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
        val items = response?.items ?: emptyList()
        val results = ArrayList<SearchResponse>()
        for (i in 0 until items.size) {
            val res = items[i].toSearchResult()
            if (res != null) {
                results.add(res)
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url).parsedSafe<DetailResponse>()?.movie ?: return null

        val title = response.name ?: return null
        val poster = response.posterUrl ?: response.thumbUrl
        val description = response.description?.replace(Regex("<.*?>"), "")

        val episodeMap = LinkedHashMap<String, MutableList<EpisodeData>>()
        val servers = response.episodes ?: emptyList()

        for (i in 0 until servers.size) {
            val server = servers[i]
            val serverName = server.serverName ?: "Server"
            val epItems = server.items ?: emptyList()
            for (j in 0 until epItems.size) {
                val item = epItems[j]
                val epName = item.name ?: "Full"
                var epList = episodeMap[epName]
                if (epList == null) {
                    epList = ArrayList()
                    episodeMap[epName] = epList
                }
                epList.add(
                    EpisodeData(
                        serverName = serverName,
                        m3u8 = item.m3u8,
                        embed = item.embed
                    )
                )
            }
        }

        val episodes = ArrayList<Episode>()
        var index = 1
        for ((epName, epDataList) in episodeMap) {
            val dataJson = epDataList.toJson()
            val digits = epName.filter { it.isDigit() }.toIntOrNull() ?: index
            val epObj = newEpisode(dataJson) {
                this.name = if (epName.lowercase().contains("tập")) epName else "Tập $epName"
                this.episode = digits
            }
            episodes.add(epObj)
            index++
        }

        val isMovie = response.totalEpisodes == 1 && episodes.size == 1

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes[0].data) {
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

        for (i in 0 until sources.size) {
            val source = sources[i]
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
                        this.quality = 1080
                    }
                )
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

    private fun fixLink(url: String?): String? {
        if (url == null) return null
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }

    private fun parseElementsToSearch(elements: Elements): List<SearchResponse> {
        val list = ArrayList<SearchResponse>()
        val seenUrls = HashSet<String>()
        for (i in 0 until elements.size) {
            val item = parseSearchItem(elements.get(i))
            if (item != null && seenUrls.add(item.url)) {
                list.add(item)
            }
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val elements = document.select("article, .halim-item, .movie-item, .film-item, .item")
        val items = parseElementsToSearch(elements)
        return newHomePageResponse(request.name, items)
    }

    private fun parseSearchItem(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a[href]") ?: return null
        val href = fixLink(linkElement.attr("href")) ?: return null
        if (href.contains("/the-loai/") || href.contains("/moi-cap-nhat")) return null

        val rawTitle = element.selectFirst(".entry-title, .title, .film-name, h2, h3")?.text()?.trim()
        val fallbackTitle = linkElement.attr("title").trim()
        val finalTitle = if (!rawTitle.isNullOrEmpty()) rawTitle else if (fallbackTitle.isNotEmpty()) fallbackTitle else linkElement.text().trim()
        if (finalTitle.isEmpty()) return null

        val imgElement = element.selectFirst("img")
        val dataSrc = imgElement?.attr("data-src")?.trim()
        val lazySrc = imgElement?.attr("data-lazy-src")?.trim()
        val normalSrc = imgElement?.attr("src")?.trim()

        val chosenPoster = when {
            !dataSrc.isNullOrEmpty() -> dataSrc
            !lazySrc.isNullOrEmpty() -> lazySrc
            else -> normalSrc
        }
        val posterUrl = fixLink(chosenPoster)
        val epText = element.selectFirst(".episode, .status, .halim-episode, .ep")?.text()?.trim()

        return newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/tim-kiem?keyword=$encodedQuery").document
        val elements = document.select("article, .halim-item, .movie-item, .film-item, .item")
        return parseElementsToSearch(elements)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val h1Title = document.selectFirst("h1, .entry-title, .film-title")?.text()?.trim()
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
        val title = if (!h1Title.isNullOrEmpty()) h1Title else ogTitle ?: return null

        val ogImg = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImg = document.selectFirst(".movie-poster img, .poster img, .halim-movie-poster img")?.attr("src")
        val poster = fixLink(if (!ogImg.isNullOrEmpty()) ogImg else posterImg)

        val descEl = document.selectFirst(".entry-content, .film-description, .desc, meta[property=og:description]")
        val description = if (descEl != null) {
            if (descEl.tagName() == "meta") descEl.attr("content").trim() else descEl.text().trim()
        } else null

        var epElements = document.select(".halim-list-eps a, #list-episodes a, .list-episode a, .episodes a")

        if (epElements.isEmpty()) {
            val watchHref = document.selectFirst("a:contains(Xem Thuyết Minh), a:contains(Xem Vietsub), a:contains(Xem Phim), a.btn-watch")?.attr("href")
            val watchUrl = fixLink(watchHref)
            if (watchUrl != null && watchUrl != url) {
                val watchDoc = app.get(watchUrl).document
                epElements = watchDoc.select(".halim-list-eps a, #list-episodes a, .list-episode a, .episodes a")
            }
        }

        val episodes = ArrayList<Episode>()
        val seenEpData = HashSet<String>()

        for (i in 0 until epElements.size) {
            val ep = epElements.get(i)
            val epHref = fixLink(ep.attr("href"))
            if (epHref != null && seenEpData.add(epHref)) {
                val rawName = ep.text().trim()
                val epName = if (rawName.isNotEmpty()) rawName else "Full"
                val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: (i + 1)
                val epObj = newEpisode(epHref) {
                    this.name = if (epName.lowercase().contains("tập")) epName else "Tập $epName"
                    this.episode = epNum
                }
                episodes.add(epObj)
            }
        }

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

        val m3u8Regex = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
        val foundLinks = HashSet<String>()

        val matches = m3u8Regex.findAll(html).toList()
        for (i in 0 until matches.size) {
            val cleanUrl = matches[i].value.replace("\\/", "/")
            foundLinks.add(cleanUrl)
        }

        val iframes = document.select("iframe[src]")
        for (i in 0 until iframes.size) {
            val iframeSrc = fixLink(iframes.get(i).attr("src"))
            if (iframeSrc != null) {
                val iframeHtml = runCatching { app.get(iframeSrc, referer = "$mainUrl/").text }.getOrNull()
                if (iframeHtml != null) {
                    val subMatches = m3u8Regex.findAll(iframeHtml).toList()
                    for (j in 0 until subMatches.size) {
                        val cleanUrl = subMatches[j].value.replace("\\/", "/")
                        foundLinks.add(cleanUrl)
                    }
                }
            }
        }

        var index = 1
        for (m3u8Url in foundLinks) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - Server $index",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = 1080
                }
            )
            index++
        }

        return true
    }
}
