package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsMangaTranslatorAuthRoute : NavKey

@Composable
fun SettingsMangaTranslatorAuthScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val backVisibility = LocalBackButtonVisibility.current
    val prefs = remember { globalAppGraph.translationPreferences }
    val service = remember { globalAppGraph.mangaTranslatorService }
    val scope = rememberCoroutineScope()

    val savedEmailState = prefs.mangaTranslatorEmail().collectAsState()
    val savedEmail = savedEmailState.value
    val hasTokenState = prefs.mangaTranslatorAccessToken().collectAsState()
    val hasToken = hasTokenState.value
    var email by remember { mutableStateOf(savedEmail) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Keep the email field in sync when navigating back.
    LaunchedEffect(savedEmail) { email = savedEmail }

    // Fetch the current user on enter if a session already exists.
    LaunchedEffect(hasToken) {
        if (hasToken.isNotBlank()) {
            status = context.stringResource(KMR.strings.manga_translator_checking_session)
            try {
                val user = service.getCurrentUser()
                status = if (user != null) {
                    context.stringResource(
                        KMR.strings.manga_translator_logged_in_as,
                        user.email ?: context.stringResource(KMR.strings.manga_translator_anonymous_name),
                        user.subscriptionTier,
                    )
                } else {
                    context.stringResource(KMR.strings.manga_translator_session_stale)
                }
            } catch (e: Exception) {
                status = context.stringResource(KMR.strings.manga_translator_session_failed, e.message.orEmpty())
            }
        } else {
            status = context.stringResource(KMR.strings.manga_translator_not_logged_in)
        }
    }

    fun signIn() {
        busy = true
        scope.launch {
            try {
                status = when (val result = service.login(email.trim(), password)) {
                    exh.yakuyomi.LoginResult.Success -> {
                        prefs.mangaTranslatorEmail().set(email.trim())
                        context.toast(KMR.strings.manga_translator_signed_in_toast)
                        context.stringResource(
                            KMR.strings.manga_translator_login_success,
                            service.getCurrentUser()?.subscriptionTier
                                ?: context.stringResource(KMR.strings.manga_translator_free_tier),
                        )
                    }
                    exh.yakuyomi.LoginResult.BadPassword ->
                        context.stringResource(KMR.strings.manga_translator_login_bad_password)
                    exh.yakuyomi.LoginResult.UnknownEmail ->
                        context.stringResource(KMR.strings.manga_translator_login_unknown_email)
                    exh.yakuyomi.LoginResult.InvalidEmail ->
                        context.stringResource(KMR.strings.manga_translator_invalid_email)
                    exh.yakuyomi.LoginResult.RateLimited ->
                        context.stringResource(KMR.strings.manga_translator_rate_limited)
                    exh.yakuyomi.LoginResult.Unknown ->
                        context.stringResource(KMR.strings.manga_translator_login_unknown)
                }
            } finally {
                busy = false
            }
        }
    }

    fun signUp() {
        busy = true
        scope.launch {
            try {
                status = when (val result = service.signup(email.trim(), password)) {
                    exh.yakuyomi.SignupResult.Success -> {
                        prefs.mangaTranslatorEmail().set(email.trim())
                        context.toast(KMR.strings.manga_translator_signed_up_toast)
                        context.stringResource(KMR.strings.manga_translator_signup_success)
                    }
                    exh.yakuyomi.SignupResult.EmailTaken ->
                        context.stringResource(KMR.strings.manga_translator_signup_email_taken)
                    exh.yakuyomi.SignupResult.InvalidEmail ->
                        context.stringResource(KMR.strings.manga_translator_invalid_email)
                    exh.yakuyomi.SignupResult.RateLimited ->
                        context.stringResource(KMR.strings.manga_translator_rate_limited)
                    exh.yakuyomi.SignupResult.Unknown ->
                        context.stringResource(KMR.strings.manga_translator_signup_failed)
                }
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        busy = true
        scope.launch {
            try {
                service.logout()
                status = context.stringResource(KMR.strings.manga_translator_signed_out)
                context.toast(KMR.strings.manga_translator_signed_out_toast)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.manga_translator_account_title),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(KMR.strings.manga_translator_account_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (hasToken.isNotBlank()) {
                    stringResource(KMR.strings.manga_translator_logged_in_token, hasToken.take(6))
                } else {
                    stringResource(KMR.strings.manga_translator_anonymous)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasToken.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            status?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(KMR.strings.manga_translator_email)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(KMR.strings.manga_translator_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )

            val credentialsReady = !busy && email.contains("@") && password.length >= 6
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = credentialsReady,
                    onClick = ::signIn,
                ) {
                    Text(stringResource(KMR.strings.manga_translator_sign_in))
                }
                OutlinedButton(
                    enabled = credentialsReady,
                    onClick = ::signUp,
                ) {
                    Text(stringResource(KMR.strings.manga_translator_sign_up))
                }
                TextButton(
                    enabled = !busy && hasToken.isNotBlank(),
                    onClick = ::signOut,
                ) {
                    Text(stringResource(KMR.strings.manga_translator_sign_out))
                }
            }

            Text(
                text = stringResource(KMR.strings.manga_translator_fingerprint_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
