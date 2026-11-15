package com.nguonc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Nguồn C (phim.nguonc.com) - dùng API JSON công khai, không cần đăng ký hay khóa API.
class NguonCProvider : MainAPI() {

    companion object {
        // Nếu NguonC đổi tên miền thì chỉ cần sửa dòng này
        const val SITE = "https://phim.nguonc.com"
    }

    override var mainUrl = SITE
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // Các hàng ở trang chủ: "đường dẫn API" to "tên hàng"
    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim mới cập nhật",
        "danh-sach/phim-dang-chieu" to "Phim đang chiếu",
        "danh-sach/phim-le" to "Phim lẻ",
        "danh-sach/phim-bo" to "Phim bộ",
        "danh-sach/tv-shows" to "TV Shows",
        "the-loai/hoat-hinh" to "Hoạt hình",
        "quoc-gia/han-quoc" to "Phim Hàn Quốc",
        "quoc-gia/trung-quoc" to "Phim Trung Quốc",
        "quoc-gia/au-my" to "Phim Âu Mỹ"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json"
    )

    // Từ khóa để phân biệt "Thuyết minh / Lồng tiếng" (dub) và "Vietsub" (sub)
    private val dubWords = listOf(
        "thuyết minh", "thuyet minh", "thuyetminh", "lồng tiếng", "long tieng", "dubbed"
    )
    private val subWords = listOf(
        "vietsub", "viet sub", "phụ đề", "phu de", "subbed"
    )

    // ---------- Hàm phụ ----------

    // Đọc chuỗi từ JSON, trả về null nếu rỗng hoặc là null
    private fun JSONObject.str(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    // Gọi API, lỗi thì trả về null (không làm sập app)
    private suspend fun apiGet(path: String): JSONObject? {
        return try {
            JSONObject(app.get("$SITE$path", headers = apiHeaders).text)
        } catch (e: Exception) {
            null
        }
    }

    private fun kindOf(text: String): String {
        val t = text.lowercase()
        val d = dubWords.maxOf { t.lastIndexOf(it) }
        val s = subWords.maxOf { t.lastIndexOf(it) }
        return when {
            d < 0 && s < 0 -> ""
            d > s -> "dub"
            else -> "sub"
        }
    }

    private fun stripHtml(s: String): String {
        return s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
    }

    private suspend fun toSearchResponse(item: JSONObject): SearchResponse? {
        val slug = item.str("slug") ?: return null
        val title = item.str("name") ?: return null
        val poster = fixUrlNull(item.str("thumb_url") ?: item.str("poster_url"))
        val total = item.optInt("total_episodes", 1)

        // Nhãn DUB / SUB + số tập hiện ngoài poster
        val langText = (item.str("language") ?: "").lowercase()
        val hasDub = dubWords.any { langText.contains(it) }
        val hasSub = subWords.any { langText.contains(it) } || !hasDub
        val epCount = item.str("current_episode")
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val type = if (total > 1) TvType.TvSeries else TvType.Movie
        return newAnimeSearchResponse(title, "$SITE/phim/$slug", type) {
            this.posterUrl = poster
            addDubStatus(
                hasDub,
                hasSub,
                if (hasDub) epCount else null,
                if (hasSub) epCount else null
            )
        }
    }

    private suspend fun parseItems(json: JSONObject): List<SearchResponse> {
        val arr = json.optJSONArray("items")
            ?: json.optJSONObject("data")?.optJSONArray("items")
            ?: return emptyList()
        val result = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val res = toSearchResponse(obj) ?: continue
            result.add(res)
        }
        return result
    }

    // ---------- Trang chủ ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = apiGet("/api/films/${request.data}?page=$page")
        val items = if (json != null) parseItems(json) else emptyList()
        val paginate = json?.optJSONObject("paginate")
        val hasNext = if (paginate != null) {
            paginate.optInt("current_page", page) < paginate.optInt("total_page", page)
        } else {
            items.isNotEmpty()
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------- Tìm kiếm ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query, "UTF-8")
        val json = apiGet("/api/films/search?keyword=$keyword") ?: return emptyList()
        return parseItems(json)
    }

    // ---------- Trang chi tiết phim ----------

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val json = apiGet("/api/film/$slug") ?: return null
        val movie = json.optJSONObject("movie") ?: return null

        val title = movie.str("name") ?: return null
        val poster = fixUrlNull(movie.str("thumb_url") ?: movie.str("poster_url"))
        val background = fixUrlNull(movie.str("poster_url"))
        val plot = movie.str("description")?.let { stripHtml(it) }
        val totalEpisodes = movie.optInt("total_episodes", 0)
        val langText = (movie.str("language") ?: "").lowercase()
        val actors = movie.str("casts")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()

        // Năm + thể loại
        var year: Int? = null
        val tags = ArrayList<String>()
        val category = movie.optJSONObject("category")
        if (category != null) {
            val keys = category.keys()
            while (keys.hasNext()) {
                val group = category.optJSONObject(keys.next()) ?: continue
                val groupName = group.optJSONObject("group")?.str("name") ?: ""
                val list = group.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val itemName = list.optJSONObject(i)?.str("name") ?: continue
                    if (groupName.contains("Năm", ignoreCase = true)) {
                        year = itemName.toIntOrNull()
                    } else if (groupName.contains("Thể loại", ignoreCase = true)) {
                        tags.add(itemName)
                    }
                }
            }
        }

        // Nếu tên server không ghi rõ loại thì đoán theo ngôn ngữ của phim
        val langHasDub = dubWords.any { langText.contains(it) }
        val langHasSub = subWords.any { langText.contains(it) }
        val defaultKind = if (langHasDub && !langHasSub) "dub" else "sub"

        // Gom các tập: mỗi tập có thể có nhiều server, tách riêng Thuyết minh và Vietsub
        val dubMap = LinkedHashMap<String, JSONArray>()
        val subMap = LinkedHashMap<String, JSONArray>()
        val allLinks = JSONArray()
        val epNames = LinkedHashSet<String>()
        val servers = movie.optJSONArray("episodes") ?: json.optJSONArray("episodes")
        if (servers != null) {
            for (s in 0 until servers.length()) {
                val server = servers.optJSONObject(s) ?: continue
                val serverName = server.str("server_name") ?: "Server ${s + 1}"
                val kind = kindOf(serverName).ifEmpty { defaultKind }
                val target = if (kind == "dub") dubMap else subMap
                val items = server.optJSONArray("items")
                    ?: server.optJSONArray("server_data") ?: continue
                for (i in 0 until items.length()) {
                    val ep = items.optJSONObject(i) ?: continue
                    val epName = ep.str("name") ?: "${i + 1}"
                    val link = JSONObject()
                    link.put("server", serverName)
                    link.put("m3u8", ep.str("m3u8") ?: ep.str("link_m3u8") ?: "")
                    link.put("embed", ep.str("embed") ?: ep.str("link_embed") ?: "")
                    target.getOrPut(epName) { JSONArray() }.put(link)
                    allLinks.put(link)
                    epNames.add(epName)
                }
            }
        }

        val isSeries = epNames.size > 1 || totalEpisodes > 1
        val fullUrl = "$SITE/phim/$slug"

        if (!isSeries) {
            // Phim lẻ: một nút xem, chọn server (Vietsub / Thuyết minh) ở màn hình chọn nguồn
            return newMovieLoadResponse(title, fullUrl, TvType.Movie, allLinks.toString()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        }

        val dubEpisodes = buildEpisodes(dubMap)
        val subEpisodes = buildEpisodes(subMap)
        val type = if (tags.any { it.contains("hoạt hình", ignoreCase = true) }) {
            TvType.Anime
        } else {
            TvType.TvSeries
        }

        return newAnimeLoadResponse(title, fullUrl, type) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            addActors(actors)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    private suspend fun buildEpisodes(map: Map<String, JSONArray>): List<Episode> {
        val list = ArrayList<Episode>()
        for ((epName, links) in map) {
            val number = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            val label = if (epName.all { it.isDigit() }) "Tập $epName" else epName
            list.add(
                newEpisode(links.toString()) {
                    this.name = label
                    this.episode = number
                }
            )
        }
        list.sortBy { it.episode ?: Int.MAX_VALUE }
        return list
    }

    // ---------- Lấy link phát ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val arr = try {
            JSONArray(data)
        } catch (e: Exception) {
            return false
        }

        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val serverName = obj.str("server") ?: name
            val m3u8 = obj.str("m3u8")
            val embed = obj.str("embed")

            if (m3u8 != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = serverName,
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$SITE/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else if (embed != null) {
                if (loadExtractor(embed, "$SITE/", subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }
}
