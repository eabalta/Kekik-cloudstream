// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup

class DiziKorea : MainAPI() {
    override var mainUrl            = "https://dizikorea.tv"
    override var name               = "DiziKorea"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = true
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-kore-dizileri/"   to "Kore Dizileri",
        "${mainUrl}/kore-filmleri-izle1/" to "Kore Filmleri",
        "${mainUrl}/tayland-dizileri/"    to "Tayland Dizileri",
        "${mainUrl}/tayland-filmleri/"    to "Tayland Filmleri",
        "${mainUrl}/cin-dizileri/"        to "Çin Dizileri",
        "${mainUrl}/cin-filmleri/"        to "Çin Filmleri",
        "${mainUrl}/yabanci-dizi/"        to "Yabancı Dizi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.poster-long").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h2")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "${mainUrl}/",
            data    = mapOf("query" to query)
        ).parsedSafe<KoreaSearch>()!!.theme

        val document = Jsoup.parse(response)
        val results  = mutableListOf<SearchResponse>()

        document.select("ul li").forEach { listItem ->
            val href = listItem.selectFirst("a")?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) {
                val result = listItem.toPostSearchResult()
                result?.let { results.add(it) }
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1 a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src")) ?: return null
        val year        = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
        val tags        = document.select("div.series-profile-type a").mapNotNull { it?.text()?.trim() }
        val rating      = document.selectFirst("span.color-imdb")?.text()?.trim()?.toRatingInt()
        val duration    = document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim().split(" ").first().toIntOrNull()
        val trailer     = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors      = document.select("div.series-profile-cast li").map {
            Actor(it.selectFirst("h5")!!.text(), it.selectFirst("img")!!.attr("data-src"))
        }

        if (url.contains("/dizi/")) {
            val episodes    = mutableListOf<Episode>()
            document.select("div.series-profile-episode-list").forEach {
                val ep_season = it.parent()!!.id().split("-").last().toIntOrNull()

                it.select("li").forEach ep@ { episodeElement ->
                    val ep_href    = fixUrlNull(episodeElement.selectFirst("h6 a")?.attr("href")) ?: return@ep
                    val ep_episode = episodeElement.selectFirst("a.truncate data")?.text()?.trim()?.toIntOrNull()

                    episodes.add(Episode(
                        data    = ep_href,
                        name    = "${ep_season}. Sezon ${ep_episode}. Bölüm",
                        season  = ep_season,
                        episode = ep_episode
                    ))
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.AsianDrama, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZK", "data » ${data}")
        val document = app.get(data).document


        document.select("div.series-watch-alternatives button").forEach {
            var iframe = it.attr("data-frame")
            if (iframe.startsWith("//")) {
                iframe = "https:${iframe}"
            }
            Log.d("DZK", "iframe » ${iframe}")

            if (iframe.contains("vidmoly")) {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            } else if (iframe.contains("videoseyred")) {
                val video_id = iframe.substringAfter("embed/")
                Log.d("DZK", "video_id » ${video_id}")

                var response = app.get("https://videoseyred.in/playlist/${video_id}.json", referer="${mainUrl}/").parsedSafe<List<VideoSeyred>>()!!.first()
                Log.d("DZK", "response » ${response}")

                if (response.tracks.isNotEmpty()) {
                    response.tracks.forEach { track ->
                        if (track.kind == "captions") {
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = track.label ?: "",
                                    url  = fixUrl(track.file)
                                )
                            )
                        }
                    }
                }

                if (response.sources.isNotEmpty()) {
                    response.sources.forEach { source ->
                        callback.invoke(
                            ExtractorLink(
                                source  = "VideoSeyred",
                                name    = "VideoSeyred",
                                url     = source.file,
                                referer = "${mainUrl}/",
                                quality = Qualities.Unknown.value,
                                isM3u8  = source.file.contains(".m3u8")
                            )
                        )
                    }
                }
            }
        }

        return true
    }
}
