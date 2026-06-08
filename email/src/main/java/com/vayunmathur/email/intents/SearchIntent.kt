package com.vayunmathur.email.intents

import androidx.core.text.HtmlCompat
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.serializer

class SearchIntent : AssistantIntent<EmailSearchQuery, List<EmailData>>(serializer<EmailSearchQuery>(), serializer<List<EmailData>>()) {

    override suspend fun performCalculation(input: EmailSearchQuery): List<EmailData> {
        val dao = EmailDatabase.getInstance(this).emailDao()
        return dao.searchMessages(input.query).map { msg ->
            EmailData(
                subject = msg.subject,
                from = msg.from,
                to = msg.to,
                date = msg.date,
                body = msg.body?.let { body ->
                    val plain = if (msg.isHtml) HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                    else body
                    plain.take(2000)
                },
                isRead = msg.isRead
            )
        }
    }
}
