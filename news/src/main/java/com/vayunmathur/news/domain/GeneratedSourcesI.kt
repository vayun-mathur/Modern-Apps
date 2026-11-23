package com.vayunmathur.news.domain

// ===== BEGIN GENERATED SOURCES (analysis/news-scale/curate.py) - part I =====
// Bulk-verified 2026-11-23 (feed 200+parse, sampled article held the
// full body - same bar as the curated entries in SourceCatalog.kt).
// Round 1: Wikipedia lists + GitHub RSS dirs (curate.py). Round 2:
// world-newspapers / allyoucanread / ABYZ + newspaperindex (Wayback),
// Wikipedia articles, press associations, round-1 remainder
// (harvest2.py + curate_r2.py --apply, which regenerates all parts
// with round-1 + round-2 entries combined). Names: RSS/Atom channel
// title fetched once per feed; sitemaps use cleaned domain stems.
// Categories are provenance-driven (directory seed by host, then
// language/TLD/name signals): single-topic tech/business ->
// TECHNOLOGY, science -> SCIENCE (no Business category in the
// taxonomy), US state/city -> LOCAL, US national -> US, non-US
// national -> WORLD. Leakage scrub: denylist on name/feed/host + a
// seeded random-30 feed audit; drops are listed in
// analysis/news-scale/audit.json (round 1) and audit_r2.json.
// Do not hand-edit; regenerate with curate_r2.py --apply.
// See GeneratedSources.kt, which aggregates all parts as
// `generatedSources` for SourceCatalog.
internal val generatedSourcesI: List<NewsSource> = listOf(
    NewsSource("risu", "Релігійно-інформаційна служба України - Інтернет-портал про релігію", "https://risu.ua/rss.xml", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("sdk", "Сакам Да Кажам", "https://sdk.mk/index.php/mk/feed/", FeedKind.RSS, language = "mk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("subscription-svoboda-news", "Свобода", "https://subscription.svoboda-news.com/feed/", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("gazeta-sebastopol", "Севастопольская газета", "https://gazeta.sebastopol.ua/rss", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("sovsibir", "Советская Сибирь", "https://www.sovsibir.ru/rss", FeedKind.RSS, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("tabloid-pravda-com", "ТаблоID", "https://tabloid.pravda.com.ua/rss", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("tocka-com", "Точка", "https://tocka.com.mk/rss", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("tumentoday", "Тюменская область сегодня", "https://tumentoday.ru/rss", FeedKind.RSS, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("pravda-com", "Українська правда", "https://www.pravda.com.ua/rss/", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("espreso", "Український погляд: новини, відео, пояснення, репортажі, ексклюзиви ᐈ Еспресо", "https://espreso.tv/rss", FeedKind.RSS, language = "uk", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ferghana", "Фергана", "https://ferghana.ru/sitemap.xml", FeedKind.SITEMAP, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("fontanka", "ФОНТАНКА.ру - новости Санкт-Петербурга", "https://www.fontanka.ru/rss-feeds/rss.xml", FeedKind.RSS, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("chita", "ЧИТА.ру - новости Читы", "https://www.chita.ru/rss-feeds/rss.xml", FeedKind.RSS, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("expert-org", "Эксперт-Центр", "https://expert.org.ua/rss.xml", FeedKind.RSS, language = "ru", categories = listOf(NewsCategory.WORLD)),
    NewsSource("azatutyun", "Ազատ Եվրոպա/Ազատություն» ռադիոկայան", "https://www.azatutyun.am/api/", FeedKind.RSS, language = "hy", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ysu", "ԵՊՀ", "https://www.ysu.am/rss.xml", FeedKind.RSS, language = "hy", categories = listOf(NewsCategory.WORLD)),
    NewsSource("hetq", "Հետք - Լուրեր, հոդվածներ, հետաքննություններ -", "https://hetq.am/hy/rss", FeedKind.RSS, language = "hy", categories = listOf(NewsCategory.WORLD)),
    NewsSource("maki-org", "המפלגה הקומוניסטית הישראלית", "https://maki.org.il/feed/", FeedKind.RSS, language = "he", categories = listOf(NewsCategory.WORLD)),
    NewsSource("globes-co", "שוק ההון והשקעות - גלובס", "https://www.globes.co.il/webservice/rss/rssfeeder.asmx/FeederNode?iID=585", FeedKind.RSS, language = "he", categories = listOf(NewsCategory.WORLD)),
    NewsSource("arabic-euronews", "آخر الأخبار من يورونيوز", "https://arabic.euronews.com/rss", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("aqlame", "أقلام - aqlame", "https://aqlame.com/rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("anbaa", "أنباء", "https://anbaa.info/?feed=rss2", FeedKind.RSS, language = "ary", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ikhwanonline", "إخوان أونلاين", "https://www.ikhwanonline.com/rss", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("elaph", "إيلاف - أخبار عاجلة", "https://elaph.com/feed", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("akhbar7", "اخبار 7 جريدة اخبارية مغربية", "https://akhbar7.ma/feed", FeedKind.RSS, language = "ary", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ettelaat", "اطلاعات آنلاین", "https://www.ettelaat.com/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("elbadil", "البديل - صحيفة ألكترونية تصدر من موريتانيا", "https://www.elbadil.info/rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("elhourriya", "الحرية نت", "https://elhourriya.net/?feed=rss2", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("arayalmostenir", "الرأي المستنير", "https://arayalmostenir.com/rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("larache24", "العرائش 24", "https://larache24.net/?feed=rss2", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("laayounenow", "العيون الآن", "https://laayounenow.com/feed/", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("almashhad", "المشهد اليمني", "https://www.almashhad.news/feed", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ibna", "ایبنا", "https://www.ibna.ir/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("bajnews", "باج نيوز", "https://bajnews.net/feed/", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("badilpress", "بديل بريس", "https://badilpress.com/?feed=rss2", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ouarsenis", "بوابة الونشريس | ملتقى الإبداع و التواصل", "https://ouarsenis.com/sitemap.xml", FeedKind.SITEMAP, categories = listOf(NewsCategory.WORLD)),
    NewsSource("parsi-euronews", "تازه‌ترین خبرها از یورونیوز", "https://parsi.euronews.com/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("ar-telquel", "تيل كيل عربي", "http://ar.telquel.ma/feed/", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("vetogate", "جميع الموضوعات", "https://www.vetogate.com/rss.aspx", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("elbalad", "جميع الموضوعات - صدى البلد", "https://www.elbalad.news/rss.aspx", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("irna", "خبرگزاری جمهوری اسلامی |صفحه اصلی", "https://www.irna.ir/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("icana", "خبرگزاری خانه ملت", "https://www.icana.ir/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("mehrnews", "خبرگزاری مهر", "https://www.mehrnews.com/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("daralhayat", "دار الحياة", "http://www.daralhayat.com/sitemap.xml", FeedKind.SITEMAP, categories = listOf(NewsCategory.WORLD)),
    NewsSource("dailybaakhabar", "روزنامہ باخبر", "https://dailybaakhabar.com/feed/", FeedKind.RSS, language = "ur", categories = listOf(NewsCategory.WORLD)),
    NewsSource("zamanalwsl", "زمان الوصل", "https://www.zamanalwsl.net/rss", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("raya", "شبكة راية الاعلامية", "https://www.raya.ps/rss", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("alweeam-com", "صحيفة الوئام", "https://www.alweeam.com.sa/feed/", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("mourassiloun", "صحيفة مراسلون", "https://mourassiloun.com/rss2.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("aksalser", "عكس السير", "https://www.aksalser.com/news/feed/", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("qudsonline", "قدس آنلاین", "https://www.qudsonline.ir/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("rimtoday", "موريتانيا اليوم", "https://rimtoday.net/?q=rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("altpresse", "موقع التبريس الاخباري", "https://altpresse.com/?feed=rss2", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("meyadin", "ميادين", "https://www.meyadin.net/rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("hamshahrionline", "همشهری آنلاین، سایت خبری روزنامه همشهری", "https://www.hamshahrionline.ir/rss", FeedKind.RSS, language = "fa", categories = listOf(NewsCategory.WORLD)),
    NewsSource("akhbarlubnan", "وكالة أخبار لبنان", "https://akhbarlubnan.net/?feed=rss2", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("anbaatlas", "وكالة أنباء أطلس", "https://anbaatlas.com/rss.xml", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("wattan", "وكالة وطن للأنباء", "https://www.wattan.net/rss", FeedKind.RSS, language = "ar", categories = listOf(NewsCategory.WORLD)),
    NewsSource("kabulpress", "کابل پرس: خبری، تحليلی و انتقادی", "https://kabulpress.org/sitemap.xml", FeedKind.SITEMAP, categories = listOf(NewsCategory.WORLD)),
    NewsSource("nepalipost", "नेपालीपोष्ट", "http://nepalipost.com/beta/feed", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("crimebarta", "ক্রাইম বার্তা", "https://crimebarta.com/feed/", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("purbanchal", "দৈনিক পূর্বাঞ্চল", "https://purbanchal.com/feed/", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("naewna", "สำนักข่าวแนวหน้า มั่นคง ตรงไป ตรงมา", "https://www.naewna.com/n/feed/", FeedKind.RSS, language = "th", categories = listOf(NewsCategory.WORLD)),
    NewsSource("resonancedaily", "გაზეთ რეზონანსის ინტერნეტ-პორტალი", "http://www.resonancedaily.com/sitemap.xml", FeedKind.SITEMAP, categories = listOf(NewsCategory.LOCAL), region = "Georgia, United States"),
    NewsSource("thetidenewsonline", "…The Tide News Online:::…", "https://www.thetidenewsonline.com/feed/", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("saipantribune", "❯", "https://www.saipantribune.com/index.php/feed/", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("81", "中国军网/中国军网", "http://www.81.cn/rss.xml", FeedKind.RSS, language = "zh", categories = listOf(NewsCategory.WORLD)),
    NewsSource("leovan", "中文博客 on 范叶亮", "https://leovan.me/cn/index.xml", FeedKind.RSS, language = "zh-HANS", categories = listOf(NewsCategory.WORLD)),
    NewsSource("tcnn-org", "台灣教會公報新聞網", "https://tcnn.org.tw/en/feed", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("visiontimesjp", "看中国 / VisionTimesJP", "https://www.visiontimesjp.com/?feed=rss2", FeedKind.RSS, language = "ja", categories = listOf(NewsCategory.WORLD)),
    NewsSource("secretchina", "看中國 http://www.secretchina.com", "https://www.secretchina.com/news/gb/news.xml", FeedKind.RSS, categories = listOf(NewsCategory.WORLD)),
    NewsSource("gtdstudy", "纸短情长", "https://www.gtdstudy.com/index.xml", FeedKind.RSS, language = "zh-CN", categories = listOf(NewsCategory.WORLD)),
    NewsSource("udn", "聯合新聞網：觸動未來 新識力", "https://udn.com/news/rssfeed/", FeedKind.RSS, language = "zh-TW", categories = listOf(NewsCategory.WORLD)),
    NewsSource("yushuangqi", "虞双齐的博客", "https://yushuangqi.com/index.xml", FeedKind.RSS, language = "zh", categories = listOf(NewsCategory.WORLD)),
    NewsSource("kmib-co", "국민일보", "https://www.kmib.co.kr/rss/data/kmibRssAll.xml", FeedKind.RSS, language = "ko", categories = listOf(NewsCategory.WORLD)),
    NewsSource("sportschosun", "스포츠조선 : 전체뉴스", "https://www.sportschosun.com/rss/index_all.htm", FeedKind.RSS, language = "ko", categories = listOf(NewsCategory.WORLD)),
    NewsSource("yna-co", "연합뉴스 최신기사", "https://www.yna.co.kr/rss/news.xml", FeedKind.RSS, language = "ko", categories = listOf(NewsCategory.WORLD)),
    NewsSource("hani-co", "전체기사 : 뉴스 : 한겨레 뉴스 - 인터넷한겨레", "https://www.hani.co.kr/rss", FeedKind.RSS, language = "ko", categories = listOf(NewsCategory.WORLD)),
    NewsSource("journalist-or", "한국기자협회 : 최신기사", "http://www.journalist.or.kr/data/rss/news.xml", FeedKind.RSS, language = "ko", categories = listOf(NewsCategory.WORLD)),
)

// ===== END GENERATED SOURCES - part I =====
