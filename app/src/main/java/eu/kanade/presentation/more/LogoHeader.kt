package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import mihon.app.di.globalAppGraph
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun LogoHeader() {
    // KMK --> The custom launcher name is allowed to surface here: it is an identity choice, and
    // About is the one place inside the app that names the app at all.
    val customName by globalAppGraph.appIconManager.customAppName.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_houri),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 56.dp, bottom = if (customName.isBlank()) 56.dp else 12.dp)
                .size(64.dp),
        )
        if (customName.isNotBlank()) {
            Text(
                text = customName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 40.dp),
            )
        }

        HorizontalDivider()
    }
    // KMK <--
}
