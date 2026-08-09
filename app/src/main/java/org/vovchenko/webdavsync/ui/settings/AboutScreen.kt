package org.vovchenko.webdavsync.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.vovchenko.webdavsync.BuildConfig
import org.vovchenko.webdavsync.R
import org.vovchenko.webdavsync.ui.components.AppScaffold
import org.vovchenko.webdavsync.ui.components.WebDavSyncTitle

private const val CONTACT_EMAIL = "hummersoft@mailbox.org"

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    AppScaffold(
        title = "About",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Launcher artwork as the About logo (requirements.md: centered Logo picture).
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "WebDAV-sync logo",
                    modifier = Modifier.size(96.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            WebDavSyncTitle(
                modifier = Modifier.padding(top = 24.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(CONTACT_EMAIL)
            }
        }
    }
}
