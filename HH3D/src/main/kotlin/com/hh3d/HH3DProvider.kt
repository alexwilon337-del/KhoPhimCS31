package com.hh3d

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// HH3D = Hoạt hình Trung Quốc 3D.
// Dữ liệu lấy từ API công khai của KKPhim (phimapi.com): có link .m3u8 trực tiếp,
// và tách sẵn bản Vietsub / Thuyết minh theo tên server.
class HH3DProvider : MainAPI() {

    companion object {
        // Nếu sau này API đổi tên miền thì chỉ cần sửa 2 dòng này
        const val API = "https://phimapi.com"
        const val IMG_CDN = "https://phimimg.com"
    }

    override var mainUrl = API
    override var name = "HH3D"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )

    private val listBase =
        "/v1/api/danh-sach/hoat-hinh?country=trung-quoc&sort_field=modified.time&sort_type=desc"

    // Các hàng ở trang chủ: "đường dẫn API" to "tên hàng"
    override val mainPage = mainPageOf(
        listBase to "Hoạt hình TQ mới cập nhật",
        "$listBase&sort_lang=thuyet-minh" to "Hoạt hình TQ thuyết minh",
        "$listBase&sort_lang=vietsub" to "Hoạt hình TQ vietsub",
        "$listBase&sort_lang=long-tieng" to "Hoạt hình TQ lồng tiếng"
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
            JSONObject(app.get(API + path, headers = apiHeaders).text)
        } catch (e: Exception) {
            null
        }
    }

    private fun imgUrl(path: String?, cdn: String): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return cdn.trimEnd('/') + "/" + path.trimStart('/')
    }

    // Đoán một đoạn chữ nói về "thuyết minh" (dub), "vietsub" (sub) hay không rõ ("")
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

    private suspend fun toSearchResponse(item: JSONObject, cdn: String): SearchResponse? {
        val slug = item.str("slug") ?: return null
        val title = item.str("name") ?: return null
        val poster = imgUrl(item.str("thumb_url") ?: item.str("poster_url"), cdn)

        // Nhãn DUB / SUB + số tập hiện ngoài poster
        val langText = (item.str("lang") ?: "").lowercase()
        val hasDub = dubWords.any { langText.contains(it) }
        val hasSub = subWords.any { langText.contains(it) } || !hasDub
        val epCount = item.str("episode_current")
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, "$API/phim/$slug", TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(
                hasDub,
                hasSub,
                if (hasDub) epCount else null,
                if (hasSub) epCount else null
            )
        }
    }

    private suspend fun parseItems(json: JSONObject, animationOnly: Boolean): List<SearchResponse> {
        val root = json.optJSONObject("data") ?: json
        val arr = root.optJSONArray("items") ?: json.optJSONArray("items") ?: return emptyList()
        val cdn = root.str("APP_DOMAIN_CDN_IMAGE") ?: IMG_CDN

        val all = ArrayList<SearchResponse>()
        val animation = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val res = toSearchResponse(item, cdn) ?: continue
            all.add(res)
            if (item.str("type") == "hoathinh") animation.add(res)
        }
        return if (animationOnly && animation.isNotEmpty()) animation else all
    }

    // ---------- Trang chủ ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val json = apiGet("${request.data}${sep}page=$page&limit=24")
        val items = if (json != null) parseItems(json, false) else emptyList()

        val root = json?.optJSONObject("data") ?: json
        val pagination = root?.optJSONObject("params")?.optJSONObject("pagination")
            ?: json?.optJSONObject("pagination")
        val hasNext = if (pagination != null) {
            pagination.optInt("currentPage", page) < pagination.optInt("totalPages", page)
        } else {
            items.isNotEmpty()
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------- Tìm kiếm ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = apiGet("/v1/api/tim-kiem?keyword=$q&limit=24&page=1") ?: return emptyList()
        return parseItems(json, true)
    }

    // ---------- Trang chi tiết phim ----------

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val json = apiGet("/phim/$slug") ?: return null
        val movie = json.optJSONObject("movie") ?: return null

        val title = movie.str("name") ?: return null
        val poster = imgUrl(movie.str("thumb_url") ?: movie.str("poster_url"), IMG_CDN)
        val background = imgUrl(movie.str("poster_url"), IMG_CDN)
        val plot = movie.str("content")?.let { stripHtml(it) }
        val year = movie.optInt("year", 0).takeIf { it > 0 }
        val movieLang = (movie.str("lang") ?: "").lowercase()

        // Thể loại
        val tags = ArrayList<String>()
        val categories = movie.optJSONArray("category")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val n = categories.optJSONObject(i)?.str("name") ?: continue
                tags.add(n)
            }
        }

        // Diễn viên
        val actors = ArrayList<String>()
        val actorArr = movie.optJSONArray("actor")
        if (actorArr != null) {
            for (i in 0 until actorArr.length()) {
                val n = actorArr.optString(i).trim()
                if (n.isNotEmpty() && n != "null") actors.add(n)
            }
        }

        // Nếu tên server không ghi rõ loại thì đoán theo ngôn ngữ của phim
        val langHasDub = dubWords.any { movieLang.contains(it) }
        val langHasSub = subWords.any { movieLang.contains(it) }
        val defaultKind = if (langHasDub && !langHasSub) "dub" else "sub"

        // Gom các tập: mỗi tập có thể có nhiều server, tách riêng danh sách Thuyết minh và Vietsub
        val dubMap = LinkedHashMap<String, JSONArray>()
        val subMap = LinkedHashMap<String, JSONArray>()
        val servers = json.optJSONArray("episodes")
        if (servers != null) {
            for (s in 0 until servers.length()) {
                val server = servers.optJSONObject(s) ?: continue
                val serverName = server.str("server_name") ?: "Server ${s + 1}"
                val kind = kindOf(serverName).ifEmpty { defaultKind }
                val target = if (kind == "dub") dubMap else subMap
                val items = server.optJSONArray("server_data") ?: continue
                for (i in 0 until items.length()) {
                    val ep = items.optJSONObject(i) ?: continue
                    val epName = ep.str("name") ?: ep.str("slug") ?: "${i + 1}"
                    val link = JSONObject()
                    link.put("server", serverName)
                    link.put("m3u8", ep.str("link_m3u8") ?: "")
                    link.put("embed", ep.str("link_embed") ?: "")
                    target.getOrPut(epName) { JSONArray() }.put(link)
                }
            }
        }

        val dubEpisodes = buildEpisodes(dubMap)
        val subEpisodes = buildEpisodes(subMap)

        return newAnimeLoadResponse(title, "$API/phim/$slug", TvType.Anime) {
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
                        this.referer = "https://kkphim.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else if (embed != null) {
                if (loadExtractor(embed, "https://kkphim.com/", subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }
}
