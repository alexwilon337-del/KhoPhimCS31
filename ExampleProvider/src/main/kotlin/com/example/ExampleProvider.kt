package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup

class ExampleProvider : MainAPI() {
    // Đổi mainUrl nếu kho phim của bạn dùng domain khác (cùng cấu trúc API /api/...)
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val api get() = "$mainUrl/api"
    private val apiHeaders get() = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/films/phim-moi-cap-nhat" to "Phim mới cập nhật",
        "$mainUrl/api/films/danh-sach/phim-dang-chieu" to "Phim đang chiếu",
        "$mainUrl/api/films/danh-sach/phim-le" to "Phim lẻ",
        "$mainUrl/api/films/danh-sach/phim-bo" to "Phim bộ",
        "$mainUrl/api/films/danh-sach/tv-shows" to "TV Shows",
        "$mainUrl/api/films/quoc-gia/han-quoc" to "Hàn Quốc",
        "$mainUrl/api/films/quoc-gia/trung-quoc" to "Trung Quốc",
        "$mainUrl/api/films/quoc-gia/au-my" to "Âu Mỹ"
    )

    // ---------- Trang chủ ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(
            request.data,
            params = mapOf("page" to page.toString()),
            headers = apiHeaders
        ).parsedSafe<ListResponse>()
        val list = res?.items.orEmpty().mapNotNull { toSearch(it) }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    // ---------- Tìm kiếm ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$api/films/search",
            params = mapOf("keyword" to query),
            headers = apiHeaders
        ).parsedSafe<ListResponse>()
        return res?.items.orEmpty().mapNotNull { toSearch(it) }
    }

    private fun toSearch(item: FilmItem): SearchResponse? {
        val slug = item.slug ?: return null
        val title = item.name ?: return null
        val url = "$mainUrl/phim/$slug"
        val poster = fixUrlNull(item.thumbUrl ?: item.posterUrl)
        return if ((item.totalEpisodes ?: 1) > 1) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ---------- Chi tiết phim ----------
    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val movie = app.get("$api/film/$slug", headers = apiHeaders)
            .parsedSafe<DetailResponse>()?.movie ?: return null

        val title = movie.name ?: return null
        val poster = fixUrlNull(movie.posterUrl ?: movie.thumbUrl)
        val plotText = movie.description?.let { Jsoup.parse(it).text() }
        val actorList = movie.casts?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        // category là map {"1": {group:{name}, list:[{name}]}, ...}
        var yearValue: Int? = null
        val tagList = mutableListOf<String>()
        (movie.category as? Map<*, *>)?.values?.forEach { g ->
            val group = g as? Map<*, *> ?: return@forEach
            val groupName = (group["group"] as? Map<*, *>)?.get("name") as? String ?: ""
            val names = (group["list"] as? List<*>)?.mapNotNull {
                (it as? Map<*, *>)?.get("name") as? String
            }.orEmpty()
            when {
                groupName.contains("Năm", true) -> yearValue = names.firstOrNull()?.toIntOrNull()
                groupName.contains("Thể loại", true) -> tagList.addAll(names)
            }
        }

        // Gom các server theo tên tập
        val epMap = linkedMapOf<String, MutableList<EpLink>>()
        movie.episodes.orEmpty().forEach { server ->
            server.items.orEmpty().forEach { ep ->
                val epName = ep.name ?: return@forEach
                epMap.getOrPut(epName) { mutableListOf() }
                    .add(EpLink(server.serverName ?: "", ep.m3u8, ep.embed))
            }
        }
        if (epMap.isEmpty()) return null

        val isSeries = epMap.size > 1 || (movie.totalEpisodes ?: 1) > 1

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
                this.year = yearValue
                this.tags = tagList
                addActors(actorList)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, epMap.values.first().toJson()) {
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearValue
                this.tags = tagList
                addActors(actorList)
            }
        }
    }

    // ---------- Link phát ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<List<EpLink>>(data)
        var found = false

        for (l in links) {
            val serverName = l.server?.ifBlank { null } ?: "Server"

            if (!l.m3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, "$name $serverName", l.m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
                continue
            }

            val embed = l.embed
            if (embed.isNullOrBlank()) continue

            // Thử tìm m3u8 ngay trong trang embed
            val html = try {
                app.get(embed, referer = "$mainUrl/").text.replace("\\/", "/")
            } catch (e: Exception) {
                ""
            }
            val m3u8s = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
                .findAll(html).map { it.value }.distinct().toList()

            if (m3u8s.isNotEmpty()) {
                m3u8s.forEach { u ->
                    callback(
                        newExtractorLink(name, "$name $serverName", u, ExtractorLinkType.M3U8) {
                            this.referer = embed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                found = true
            } else if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    // ---------- Model JSON ----------
    data class ListResponse(
        @JsonProperty("items") val items: List<FilmItem>? = null
    )

    data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null
    )

    data class DetailResponse(
        @JsonProperty("movie") val movie: MovieDetail? = null
    )

    data class MovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("category") val category: Any? = null,
        @JsonProperty("episodes") val episodes: List<ServerData>? = null
    )

    data class ServerData(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<EpItem>? = null
    )

    data class EpItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )

    data class EpLink(
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}
