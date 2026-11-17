package com.vayunmathur.vpn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Text
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.domain.GeoServer

/** One imported server in the geo picker: name, provider/city and endpoint, with a connect action. */
@Composable
fun GeoServerRow(server: GeoServer, onConnect: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(server.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${server.provider} — ${server.city}", fontSize = 12.sp)
            Text(server.endpoint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Button(onClick = onConnect) { Text(stringResource(R.string.geo_connect)) }
        }
    }
}
