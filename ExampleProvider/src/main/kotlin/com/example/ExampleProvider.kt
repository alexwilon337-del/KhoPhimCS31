package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup

// mode = "vietsub" hoặc "thuyetminh": mỗi chế độ là một nguồn riêng trong app
class ExampleProvider(private val mode: String = "vietsub") : MainAPI() {
    private val isDub = mode == "thuyetminh"

    // KKPhim dùng API công khai tại phimapi.com
    override var mainUrl = "https://phimapi.com"
    override var name = if (isDub) "KKPhim Thuyết minh" else "KKPhim Vietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val siteUrl = "https://kkphim.com"
    private val imageCdn = "https://phimimg.com"

    // ---------- Đổi tên miền nhanh ----------
    // Plugin đọc file domains.json trên GitHub (mục "kkphim").
    // Khi API đổi tên miền, chỉ cần sửa file đó, không cần build lại.
    private val domainConfigUrl =
        "https://raw.githubusercontent.com/alexwilon337-del/KhoPhimCS31/master/domains.json"
    private val domainConfigKey = "kkphim"
    private var lastDomainCheck = 0L

    private suspend fun ensureDomain() {
        val now = System.currentTimeMillis()
        if (lastDomainCheck != 0L && now - lastDomainCheck < 10 * 60 * 1000L) return
        try {
            val cfg = app.get("$domainConfigUrl?t=$now", timeout = 10L)
                .parsedSafe<Map<String, String>>()
            val d = cfg?.get(domainConfigKey)?.trim()?.trimEnd('/')
            if (!d.isNullOrBlank() && d.startsWith("http")) {
                mainUrl = d
                lastDomainCheck = now
            }
        } catch (e: Exception) {
            // Không đọc được thì dùng tên miền hiện tại
        }
    }

    private val apiHeaders get() = mapOf(
        "Accept" to "application/json",
        "Referer" to "$siteUrl/"
    )

    override val mainPage = if (isDub) mainPageOf(
        "/v1/api/danh-sach/phim-thuyet-minh" to "Thuyết minh mới",
        "/v1/api/danh-sach/phim-long-tieng" to "Lồng tiếng mới",
        "/v1/api/danh-sach/phim-le?sort_lang=thuyet-minh" to "Phim lẻ",
        "/v1/api/danh-sach/phim-bo?sort_lang=thuyet-minh" to "Phim bộ",
        "/v1/api/danh-sach/hoat-hinh?sort_lang=thuyet-minh" to "Hoạt hình",
        "/v1/api/danh-sach/tv-shows?sort_lang=thuyet-minh" to "TV Shows",
        "/v1/api/quoc-gia/han-quoc?sort_lang=thuyet-minh" to "Hàn Quốc",
        "/v1/api/quoc-gia/trung-quoc?sort_lang=thuyet-minh" to "Trung Quốc",
        "/v1/api/quoc-gia/au-my?sort_lang=thuyet-minh" to "Âu Mỹ"
    ) else mainPageOf(
        "/v1/api/danh-sach/phim-vietsub" to "Vietsub mới",
        "/danh-sach/phim-moi-cap-nhat" to "Phim mới cập nhật",
        "/v1/api/danh-sach/phim-le?sort_lang=vietsub" to "Phim lẻ",
        "/v1/api/danh-sach/phim-bo?sort_lang=vietsub" to "Phim bộ",
        "/v1/api/danh-sach/hoat-hinh?sort_lang=vietsub" to "Hoạt hình",
        "/v1/api/danh-sach/tv-shows?sort_lang=vietsub" to "TV Shows",
        "/v1/api/quoc-gia/han-quoc?sort_lang=vietsub" to "Hàn Quốc",
        "/v1/api/quoc-gia/trung-quoc?sort_lang=vietsub" to "Trung Quốc",
        "/v1/api/quoc-gia/au-my?sort_lang=vietsub" to "Âu Mỹ"
    )

    // ---------- Trang chủ ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val sep = if (request.data.contains("?")) "&" else "?"
        val res = app.get(
            "$mainUrl${request.data}${sep}page=$page&limit=24",
            headers = apiHeaders
        ).parsedSafe<ListResponse>()
        val items = res?.items ?: res?.data?.items
        val cdn = res?.data?.cdn
        val list = items.orEmpty().mapNotNull { toSearch(it, cdn) }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    // ---------- Tìm kiếm ----------
    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val res = app.get(
            "$mainUrl/v1/api/tim-kiem",
            params = mapOf("keyword" to query, "limit" to "24"),
            headers = apiHeaders
        ).parsedSafe<ListResponse>()
        val items = res?.items ?: res?.data?.items
        val cdn = res?.data?.cdn
        return items.orEmpty().mapNotNull { toSearch(it, cdn) }
    }

    private fun img(path: String?, cdn: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        val base = (cdn ?: imageCdn).trimEnd('/')
        return if (path.contains("/")) "$base/${path.trimStart('/')}"
        else "$base/uploads/movies/$path"
    }

    private fun toSearch(item: FilmItem, cdn: String?): SearchResponse? {
        val slug = item.slug ?: return null
        val title = item.name ?: return null
        val url = "$siteUrl/phim/$slug"
        val poster = img(item.thumbUrl ?: item.posterUrl, cdn)
        val y = item.year
        val isSeries = item.type == "series" || item.type == "hoathinh" || item.type == "tvshows"
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = y
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = y
            }
        }
    }

    // ---------- Chi tiết phim ----------
    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val slug = url.trimEnd('/').substringAfterLast("/")
        val res = app.get("$mainUrl/phim/$slug", headers = apiHeaders)
            .parsedSafe<DetailResponse>() ?: return null
        val movie = res.movie ?: return null

        val title = movie.name ?: return null
        val poster = img(movie.posterUrl ?: movie.thumbUrl, null)
        val plotText = movie.content?.let { Jsoup.parse(it).text() }
        val actorList = movie.actor.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val durationMin = Regex("\\d+").find(movie.time ?: "")?.value?.toIntOrNull()

        val allServers = res.episodes.orEmpty()
        val keptServers = allServers.filter { keepServer(it.serverName) }
        val servers = if (keptServers.isNotEmpty()) keptServers else allServers

        val tagList = mutableListOf<String>()
        movie.category.orEmpty().mapNotNull { it.name }.forEach { tagList.add(it) }

        servers
            .map { langLabel(it.serverName) }
            .distinct()
            .filter { it != "Khác" }
            .forEach { if (!tagList.contains(it)) tagList.add(it) }

        // Gom các server theo tên tập
        val epMap = linkedMapOf<String, MutableList<EpLink>>()
        servers.forEach { server ->
            server.items.orEmpty().forEach { ep ->
                val epName = ep.name ?: return@forEach
                epMap.getOrPut(epName) { mutableListOf() }
                    .add(EpLink(server.serverName ?: "", ep.m3u8, ep.embed))
            }
        }
        if (epMap.isEmpty()) return null

        val isSeries = epMap.size > 1 || movie.type == "series" || movie.type == "tvshows"

        return if (isSeries) {
            val episodes = epMap.map { (epName, links) ->
                newEpisode(links.toJson()) {
                    this.name = epName
                    this.episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plotText
                this.year = movie.year
                this.tags = tagList
                this.duration = durationMin
                addActors(actorList)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, epMap.values.first().toJson()) {
                this.posterUrl = poster
                this.plot = plotText
                this.year = movie.year
                this.tags = tagList
                this.duration = durationMin
                addActors(actorList)
            }
        }
    }

    // Vietsub: giữ server Vietsub (và server chưa rõ ngôn ngữ). Thuyết minh: giữ Thuyết minh + Lồng tiếng
    private fun keepServer(serverName: String?): Boolean {
        val l = langLabel(serverName)
        return if (isDub) l == "Thuyết minh" || l == "Lồng tiếng"
        else l == "Vietsub" || l == "Khác"
    }

    // ---------- Nhận diện ngôn ngữ ----------
    private fun langLabel(serverName: String?): String {
        val n = (serverName ?: "").lowercase()
        return when {
            n.contains("thuyết minh") || n.contains("thuyet minh") -> "Thuyết minh"
            n.contains("lồng tiếng") || n.contains("long tieng") -> "Lồng tiếng"
            n.contains("vietsub") || n.contains("sub") -> "Vietsub"
            else -> "Khác"
        }
    }

    // ---------- Link phát ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val order = listOf("Vietsub", "Thuyết minh", "Lồng tiếng", "Khác")
        val links = parseJson<List<EpLink>>(data)
            .sortedBy { order.indexOf(langLabel(it.server)) }
        var found = false

        for (l in links) {
            val serverName = l.server?.ifBlank { null } ?: "Server"
            val lang = langLabel(l.server)
            val sourceName = name

            if (!l.m3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(sourceName, "$sourceName • $serverName", l.m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$siteUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
                continue
            }

            val embed = l.embed
            if (embed.isNullOrBlank()) continue

            // Nhiều trình phát nhúng truyền sẵn link m3u8 qua tham số ?url=
            val fromParam = Regex("""[?&]url=([^&]+)""").find(embed)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (fromParam != null && fromParam.contains(".m3u8")) {
                callback(
                    newExtractorLink(sourceName, "$sourceName • $serverName", fromParam, ExtractorLinkType.M3U8) {
                        this.referer = "$siteUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
                continue
            }

            val html = try {
                app.get(embed, referer = "$siteUrl/").text.replace("\\/", "/")
            } catch (e: Exception) {
                ""
            }
            val m3u8s = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
                .findAll(html).map { it.value }.distinct().toList()

            if (m3u8s.isNotEmpty()) {
                m3u8s.forEach { u ->
                    callback(
                        newExtractorLink(sourceName, "$sourceName • $serverName", u, ExtractorLinkType.M3U8) {
                            this.referer = embed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                found = true
            } else if (loadExtractor(embed, "$siteUrl/", subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    // ---------- Model JSON ----------
    data class ListResponse(
        @JsonProperty("items") val items: List<FilmItem>? = null,
        @JsonProperty("data") val data: ListData? = null
    )

    data class ListData(
        @JsonProperty("items") val items: List<FilmItem>? = null,
        @JsonProperty("APP_DOMAIN_CDN_IMAGE") val cdn: String? = null
    )

    data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class DetailResponse(
        @JsonProperty("movie") val movie: MovieDetail? = null,
        @JsonProperty("episodes") val episodes: List<ServerData>? = null
    )

    data class NameItem(
        @JsonProperty("name") val name: String? = null
    )

    data class MovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("content") val content: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("actor") val actor: List<String>? = null,
        @JsonProperty("category") val category: List<NameItem>? = null
    )

    data class ServerData(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("server_data") val items: List<EpItem>? = null
    )

    data class EpItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link_embed") val embed: String? = null,
        @JsonProperty("link_m3u8") val m3u8: String? = null
    )

    data class EpLink(
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}
