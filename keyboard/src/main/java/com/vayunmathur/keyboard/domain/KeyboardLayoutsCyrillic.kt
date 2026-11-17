package com.vayunmathur.keyboard.domain

/** Cyrillic-family layouts, in picker order after Latin. */
internal val cyrillicKeyboardLayouts: List<KeyboardLayout> by lazy {
    listOf(
        KeyboardLayout(
            id = "ru",
            name = "Русский",
            description = "Russian · ЙЦУКЕН",
            rows = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё", 'ь' to "ъ", 'и' to "й"),
        ),
        KeyboardLayout(
            id = "uk",
            name = "Українська",
            description = "Ukrainian · ЙЦУКЕН",
            rows = listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"),
            alternates = mapOf('г' to "ґ", 'і' to "ї", 'е' to "є", 'ь' to "'"),
        ),
        KeyboardLayout(
            id = "be",
            name = "Беларуская",
            description = "Belarusian · ЙЦУКЕН",
            rows = listOf("йцукенгшўзх'", "фывапролджэ", "ячсмітьбю"),
            alternates = mapOf('е' to "ё", 'у' to "ў", 'і' to "'"),
        ),
        KeyboardLayout(
            id = "bg",
            name = "Български",
            description = "Bulgarian · phonetic",
            rows = listOf("явертъуиопч", "асдфгхйклшщ", "зьцжбнмю"),
        ),
        KeyboardLayout(
            id = "sr",
            name = "Српски",
            description = "Serbian (Cyrillic) · ЈЦУКЕН",
            rows = listOf("љњертзуиопшђ", "асдфгхјклчћж", "џцвбнм"),
        ),
        KeyboardLayout(
            id = "mk",
            name = "Македонски",
            description = "Macedonian · ЈЦУКЕН",
            rows = listOf("љњертѕуиопшѓ", "асдфгхјклчќж", "зџцвбнм"),
        ),
        KeyboardLayout(
            id = "kk",
            name = "Қазақша",
            description = "Kazakh · ЙЦУКЕН",
            // Kazakh's nine extra letters live on the digit row of the physical layout,
            // so this is a four-row layout and gives up the keyboard's own digit row.
            rows = listOf("әіңғүұқөһ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё", 'и' to "й"),
        ),
        KeyboardLayout(
            id = "tg",
            name = "Тоҷикӣ",
            description = "Tajik · ЙЦУКЕН",
            rows = listOf("ғқӣӯҳҷ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
        KeyboardLayout(
            id = "ky",
            name = "Кыргызча",
            description = "Kyrgyz · ЙЦУКЕН",
            rows = listOf("ңөү", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
        KeyboardLayout(
            id = "tt",
            name = "Татарча",
            description = "Tatar · ЙЦУКЕН",
            rows = listOf("әөүҗңһ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
        KeyboardLayout(
            id = "ba",
            name = "Башҡортса",
            description = "Bashkir · ЙЦУКЕН",
            rows = listOf("әөүғҙҫңһҡ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
        KeyboardLayout(
            id = "mn",
            name = "Монгол",
            description = "Mongolian · ЙЦУКЕН",
            rows = listOf("өү", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
        KeyboardLayout(
            id = "cv",
            name = "Чӑвашла",
            description = "Chuvash · ЙЦУКЕН",
            rows = listOf("ӑӗҫӳ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            alternates = mapOf('е' to "ё"),
        ),
    )
}
