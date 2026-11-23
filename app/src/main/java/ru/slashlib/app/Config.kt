package ru.slashlib.app

// ВСЁ, ЧТО МОЖЕТ ПОМЕНЯТЬСЯ НА САЙТЕ, ЛЕЖИТ ЗДЕСЬ. Адреса неофициальные и на живом сервере не проверены.
object Config {
    const val API = "https://api.lib.social/api"
    const val SITE_SLASH = 2        // ПРОВЕРИТЬ: id Slashlib в сети lib.social
    const val SITE_MANGA = 1        // ПРОВЕРИТЬ: id MangaLib
    const val IMAGE_BASE = "https://img2.imglib.info"
    const val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36"

    fun referer(site: Int?): String = if (site == SITE_MANGA) "https://mangalib.me/" else "https://slashlib.me/"
    fun siteName(site: Int?): String = if (site == SITE_MANGA) "MangaLib" else "SlashLib"
    fun siteHeader(site: Int?): String = (site ?: SITE_SLASH).toString()
}
