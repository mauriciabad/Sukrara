// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Signer.launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { Signer.onResult(it) }
        val engine = Engine.get(this)
        setContent {
            MaterialTheme(colorScheme = dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { App(engine) }
            }
        }
    }
}

private enum class Screen { Welcome, NostrLogin, Home }

@Composable
private fun App(engine: Engine) {
    val state by engine.state.collectAsState()
    val busy by engine.busy.collectAsState()
    var screen by remember { mutableStateOf(if (engine.hasAccount) Screen.Home else Screen.Welcome) }
    if (state.has("root") && screen != Screen.Home) screen = Screen.Home
    if (!state.has("root") && screen == Screen.Home) screen = Screen.Welcome
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(busy!!, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        }
        when (screen) {
            Screen.Welcome -> Welcome(engine) { screen = Screen.NostrLogin }
            Screen.NostrLogin -> NostrLogin(engine) { screen = Screen.Welcome }
            Screen.Home -> Home(engine, state)
        }
    }
}

// --------------------------------------------------------------------------------------------- profile editor

/** Avatar editor shared by onboarding and the Nostr-login "separate profile" option. */
@Composable
private fun ProfileEditor(name: String, onName: (String) -> Unit, avatar: Avatar, onAvatar: (Avatar) -> Unit,
                          photo: Bitmap?, onPhoto: (Bitmap?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    // Photo Picker: no storage permission, the user picks exactly one image (developer.android.com, "Photo picker").
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { onPhoto(Media.photoForUpload(ctx, uri)) }
    }
    val shown = photo ?: remember(avatar) { avatar.render(384) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(shown.asImageBitmap(), "Profile picture", Modifier.size(128.dp).clip(CircleShape))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPhoto(null); onAvatar(Avatar.random(initial = name)) }) { Text("Shuffle") }
            OutlinedButton(onClick = { onPhoto(null); editing = !editing }) { Text(if (editing) "Done" else "Customize") }
            OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo…") }
        }
        if (editing && photo == null) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar.SHAPES.forEach { sh ->
                    FilterChip(selected = avatar.shape == sh, onClick = { onAvatar(avatar.copy(shape = sh, initial = name.take(1).uppercase())) },
                        label = { Text(if (sh == "initial") "Letter" else sh.replaceFirstChar { it.uppercase() }) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar.PALETTES.forEachIndexed { i, (a, _) ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(a))
                        .clickable { onAvatar(avatar.copy(palette = i)) }
                        .then(if (i == avatar.palette) Modifier.padding(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .6f)) else Modifier))
                }
            }
            TextButton(onClick = { onAvatar(avatar.copy(angle = (avatar.angle + 45) % 360, seed = avatar.seed + 1)) }) { Text("Rotate colours") }
        }
        OutlinedTextField(name, onName, Modifier.fillMaxWidth(), label = { Text("Name or nickname") },
            supportingText = { Text("A first name or nickname is enough.") }, singleLine = true)
    }
}

/** The always-visible notice under the editor: kind 0 is public, and copies outlive edits (live_check.py). */
@Composable
private fun PublicNotice(photo: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("Visible to everyone", fontWeight = FontWeight.SemiBold)
            Text("Your name and picture are public on Nostr, not only to the people you share with. Changing them later " +
                "doesn't erase copies already on relays and image servers.", style = MaterialTheme.typography.bodySmall)
            if (photo) Text("Location and camera details are removed from your photo before it's uploaded.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Welcome(engine: Engine, onAdvanced: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf(Avatar.random()) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var confirmPhoto by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val create = { engine.launch("create") { engine.createGenerated(name.trim(), if (photo == null) avatar else null, photo) } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your profile", style = MaterialTheme.typography.headlineSmall)
        Text("People you share your glucose with see this name and picture.")
        ProfileEditor(name, { name = it }, avatar, { avatar = it }, photo, { photo = it })
        PublicNotice(photo != null)
        Button(onClick = { if (photo != null) confirmPhoto = true else create() }, enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Create profile") }
        HorizontalDivider()
        // Advanced option, deliberately one tap away (the user asked for it to be "a bit hidden").
        TextButton(onClick = { more = !more }) { Text(if (more) "Fewer options" else "More options") }
        if (more) OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onAdvanced)) {
            Column(Modifier.padding(12.dp)) {
                Text("Use an existing Nostr account", fontWeight = FontWeight.SemiBold)
                Text("For people who already use Nostr. Read the risks first.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    // One dialog, only for the irreversible step (m3 dialogs: "purposefully interruptive, so use sparingly").
    if (confirmPhoto) AlertDialog(
        onDismissRequest = { confirmPhoto = false },
        title = { Text("Upload this photo publicly?") },
        text = { Text("Anyone on the internet will be able to see it, and copies may stay online even if you change " +
            "your picture later. A generated picture works just as well.") },
        confirmButton = { TextButton(onClick = { confirmPhoto = false; create() }) { Text("Upload publicly") } },
        dismissButton = { TextButton(onClick = { confirmPhoto = false }) { Text("Cancel") } },
    )
}

// --------------------------------------------------------------------------------------------- Nostr login

@Composable
private fun NostrLogin(engine: Engine, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var understood by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("nostr") }          // "nostr" = show my Nostr profile privately, "own"
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf(Avatar.random()) }
    var keyInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val signer = remember { Signer.installed(ctx) }
    val ready = understood && (mode == "nostr" || name.isNotBlank())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Use your Nostr account", style = MaterialTheme.typography.headlineSmall)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("Sukrara is for sharing glucose. If someone can connect your Nostr account to Sukrara, they can guess " +
                "that you have diabetes, or that someone close to you does.", Modifier.padding(12.dp))
        }
        Text("How Sukrara protects you", fontWeight = FontWeight.SemiBold)
        Bullet("It makes a separate Sukrara account from your key. Nothing public connects the two.")
        Bullet("Your Nostr key never signs or publishes anything for Sukrara.")
        Bullet("You keep one key: the same Nostr key brings back the same Sukrara account on a new phone.")
        Text("What can still give you away", fontWeight = FontWeight.SemiBold)
        Bullet("Using the same name or picture as your Nostr profile in a public Sukrara profile.")
        Bullet("People you share with, who will know your Nostr account if you show it to them.")
        Bullet("Typing your key into an app you don't fully trust. A signer app like Amber avoids that.")
        Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(understood, { understood = it })
            Text("I understand this could reveal that I have diabetes or care for someone who does.")
        }
        HorizontalDivider()
        Text("What people you share with see", fontWeight = FontWeight.SemiBold)
        listOf("nostr" to "My Nostr name and picture, shown only to them", "own" to "A separate Sukrara profile (public, like any Nostr profile)")
            .forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().selectable(mode == k, onClick = { mode = k }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == k, null); Text(label) }
            }
        if (mode == "own") {
            ProfileEditor(name, { name = it }, avatar, { avatar = it }, null, {})
            PublicNotice(false)
        }
        HorizontalDivider()
        Button(onClick = { engine.launch("signer") { engine.loginWithSigner(mode, name.trim(), avatar) } },
            enabled = ready && signer, modifier = Modifier.fillMaxWidth()) { Text("Connect a signer app") }
        if (!signer) Text("No NIP-55 signer app (such as Amber) is installed.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showKey = !showKey }, enabled = ready) { Text("Enter key or recovery words instead") }
        if (showKey && ready) {
            OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text("nsec, ncryptsec or 12/24 words") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (keyInput.startsWith("ncryptsec")) OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { engine.launch("derive") { engine.loginWithSecret(keyInput, password.ifBlank { null }, mode, name.trim(), avatar) } },
                enabled = keyInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            Text("Sukrara uses the key once to make your Sukrara account, then forgets it.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun Bullet(t: String) = Row { Text("•  "); Text(t, style = MaterialTheme.typography.bodyMedium) }

// --------------------------------------------------------------------------------------------- home

@Composable
private fun Home(engine: Engine, s: JSONObject) {
    val ctx = LocalContext.current
    val pics by engine.pictures.collectAsState()
    var join by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        engine.launch("pictures") { engine.fetchAllPictures() }
        while (true) { delay(15_000); if (engine.busy.value == null) engine.launch("poll") { engine.poll() } }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val pp = engine.profilePub()
        Section("Profile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(pics[pp], 64)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s.optString("name").ifBlank { "Your Nostr profile" }, style = MaterialTheme.typography.titleMedium)
                    Text(if (s.optString("profileMode") == "nostr") "Your Nostr profile, shown only to people you allow"
                         else "Public profile key ${pp?.let { Nostr.shortNpub(it) }}", style = MaterialTheme.typography.bodySmall)
                    s.optJSONObject("kind0")?.optJSONObject("relays")?.let { r ->
                        Text("kind 0 on ${r.keys().asSequence().count { r.getBoolean(it) }}/${r.length()} relays", style = MaterialTheme.typography.bodySmall)
                    }
                    s.optJSONObject("picture")?.let { p ->
                        Text("Picture on ${1 + (p.optJSONArray("fallbacks")?.length() ?: 0)} Blossom servers · ${p.optInt("bytes") / 1024} KB · EXIF ${if (p.optBoolean("exif")) "PRESENT" else "none"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Section("Account") {
            val origin = s.optString("origin")
            Text(when (origin) {
                "derived" -> "Derived from your Nostr key (nothing to back up besides that key)."
                "sealed" -> "Kept for you by your signer app, sealed to your Nostr key."
                else -> "New Sukrara account (back it up to your password manager)."
            })
            Mono("identity  ${Nostr.shortNpub(s.optString("identityPub"))}  (no profile, never public)")
            if (s.has("mainPub")) Mono("nostr key ${Nostr.shortNpub(s.getString("mainPub"))}  (signs nothing here)")
            if (s.has("signerCalls")) Mono("signer calls: ${s.getJSONArray("signerCalls").join(", ").replace("\"", "")}")
        }
        Section("Share") {
            Button(onClick = { engine.launch("invite") { engine.createInvite() } }) { Text("Create invite") }
            s.optString("lastInvite").takeIf { it.isNotBlank() }?.let { link ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr(link, 220) }
                Mono(link)
                Text("${link.length} characters", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Invite", link))
                }) { Text("Copy link") }
            }
        }
        Section("Follow someone") {
            OutlinedTextField(join, { join = it }, Modifier.fillMaxWidth(), label = { Text("Invite link") }, singleLine = true)
            Button(onClick = { engine.launch("join") { engine.join(join, s.optString("name").ifBlank { "Follower" }) } }, enabled = join.isNotBlank()) {
                Text("Ask to follow")
            }
            rows(s.optJSONArray("following")).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(pics[f.optString("profile")], 40)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(f.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text(if (f.optString("status") == "approved") "Allowed you" else "Waiting · code ${f.optString("code")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Section("Requests") {
            val reqs = rows(s.optJSONArray("requests"))
            if (reqs.isEmpty()) Text("No requests yet.", style = MaterialTheme.typography.bodySmall)
            reqs.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(pics[r.optString("profile")], 40)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("profileName").ifBlank { r.optString("name") }, fontWeight = FontWeight.SemiBold)
                        Text("Code ${r.optString("code")} · ${r.optString("status")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.optString("status") == "pending") {
                        TextButton(onClick = { engine.launch("deny") { engine.decide(r.getString("f"), false) } }) { Text("Deny") }
                        Button(onClick = { engine.launch("allow") { engine.decide(r.getString("f"), true) } }) { Text("Allow") }
                    }
                }
            }
            TextButton(onClick = { engine.launch("poll") { engine.poll() } }) { Text("Check now") }
        }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        if (showLog) rows2(s.optJSONArray("log")).reversed().forEach { Mono(it) }
        TextButton(onClick = { engine.launch("delete") { engine.deleteEverywhere(); engine.reset() } }) {
            Text("Delete account everywhere (asks relays and image servers to erase it)")
        }
        TextButton(onClick = { engine.reset() }) { Text("Start over (deletes this account from the phone)") }
    }
}

private fun rows(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) }
private fun rows2(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

@Composable private fun Section(title: String, content: @Composable () -> Unit) = OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable private fun Mono(t: String) = Text(t, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)

@Composable private fun AvatarImage(b: Bitmap?, dp: Int) {
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(dp.dp).clip(CircleShape))
    else Box(Modifier.size(dp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable private fun Qr(text: String, dp: Int) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    Image(bmp.asImageBitmap(), "Invite QR code", Modifier.size(dp.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
}
