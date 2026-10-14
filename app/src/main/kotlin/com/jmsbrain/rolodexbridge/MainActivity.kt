package com.jmsbrain.rolodexbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var hasAccess by mutableStateOf(false)
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAccess()
        setContent {
            Box(Modifier.fillMaxSize().background(LightStyle.background).systemBarsPadding()) {
                if (!hasAccess) AccessScreen { ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) }
                else BridgeApp(this@MainActivity)
            }
        }
    }

    private fun checkAccess() {
        hasAccess = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Keeps the screen on during the first build. */
    fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private enum class Step { SignIn, Choose, Build, Running }

@Composable
private fun BridgeApp(activity: MainActivity) {
    val ctx: Context = activity
    var step by remember {
        mutableStateOf(
            when {
                !Settings.signedIn(ctx) -> Step.SignIn
                !Settings.running(ctx) -> Step.Choose
                else -> Step.Running
            },
        )
    }
    var books by remember { mutableStateOf<List<BookInfo>>(emptyList()) }
    when (step) {
        Step.SignIn -> SignInScreen(ctx) { found -> books = found; step = Step.Choose }
        Step.Choose -> ChooseScreen(ctx, books, onNext = { step = Step.Build }, onSignOut = { step = Step.SignIn })
        Step.Build -> BuildScreen(activity, onDone = { step = Step.Running }, onBack = { step = Step.Choose })
        Step.Running -> StatusScreen(ctx, onSignOut = { step = Step.SignIn })
    }
}

@Composable
private fun AccessScreen(onAllow: () -> Unit) = Page("Rolodex Bridge") {
    Para("Keeps LightOS's contacts in step with your full mailbox.org address book, one number per entry, so calls and texts show the right name and which number it is.")
    Para("It needs access to the phone's contacts.")
    Button("ALLOW ACCESS", onAllow)
}

@Composable
private fun SignInScreen(ctx: Context, onSignedIn: (List<BookInfo>) -> Unit) {
    var email by remember { mutableStateOf(Settings.user(ctx).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Settings.server(ctx) ?: Settings.DEFAULT_SERVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Page("Sign in") {
        Para("Your mailbox.org email and password. If you use two-factor sign-in, use an app password.")
        Field("EMAIL", email, KeyboardType.Email) { email = it.trim() }
        Field("PASSWORD", password, KeyboardType.Password, secret = true) { password = it }
        Field("SERVER", server, KeyboardType.Uri) { server = it.trim() }
        error?.let { Para(it) }
        if (busy) Label("SIGNING IN…") else Button("SIGN IN") {
            busy = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dav = CardDav(email, password)
                        val home = dav.findHome(server)
                        val books = dav.addressBooks(home)
                        Settings.saveSignIn(ctx, server, email, home, password)
                        books
                    }
                }
                busy = false
                result.onSuccess(onSignedIn).onFailure { error = it.message ?: "Couldn't sign in." }
            }
        }
        Para("The password stays on this phone, encrypted, and is only sent to the server above.")
    }
}

@Composable
private fun ChooseScreen(ctx: Context, found: List<BookInfo>, onNext: () -> Unit, onSignOut: () -> Unit) {
    var serverBooks by remember { mutableStateOf(found.filter { !it.readOnly }) }
    var phoneBooks by remember { mutableStateOf<List<PhoneBookInfo>>(emptyList()) }
    var main by remember { mutableStateOf(Settings.mainBook(ctx)) }
    var light by remember { mutableStateOf(Settings.lightBook(ctx)) }
    var phoneBook by remember { mutableStateOf(Settings.phoneBook(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (serverBooks.isEmpty()) runCatching {
                serverBooks = CardDav(Settings.user(ctx)!!, Settings.password(ctx)!!).addressBooks(Settings.home(ctx)!!).filter { !it.readOnly }
            }.onFailure { error = it.message }
            phoneBooks = PhoneContacts(ctx.contentResolver).books()
        }
        if (main == null) main = serverBooks.firstOrNull { it.name.equals("Clean Contacts", true) }?.url
        if (light == null) light = serverBooks.firstOrNull { it.name.equals("Contacts Light", true) }?.url
        if (phoneBook == null) phoneBook = phoneBooks.firstOrNull()?.book
    }
    Page("Address books") {
        Label("FULL LIST (ROLODEX LIGHT)")
        serverBooks.forEach { b -> Choice(b.name, b.url == main) { main = b.url } }
        Spacer(Modifier.height(designDp(12f)))
        Label("ONE NUMBER PER ENTRY (FOR LIGHTOS)")
        Para("Kept as a copy of the full list. Anything already in it is replaced.")
        serverBooks.forEach { b -> Choice(b.name, b.url == light) { light = b.url } }
        Spacer(Modifier.height(designDp(12f)))
        Label("LIGHTOS'S ADDRESS BOOK")
        Para("Where LightOS saves new contacts. The first one listed holds the newest contact.")
        phoneBooks.forEach { b -> Choice("${shortName(b.book)} · ${b.count} contacts", b.book == phoneBook) { phoneBook = b.book } }
        error?.let { Para(it) }
        Spacer(Modifier.height(designDp(12f)))
        val m = serverBooks.firstOrNull { it.url == main }
        val l = serverBooks.firstOrNull { it.url == light }
        val p = phoneBook
        when {
            m == null || l == null || p == null -> Para("Choose all three.")
            m.url == l.url -> Para("Choose two different mailbox.org address books.")
            else -> Button("NEXT") {
                Settings.setMainBook(ctx, m.url, m.name)
                Settings.setLightBook(ctx, l.url, l.name)
                Settings.setPhoneBook(ctx, p)
                onNext()
            }
        }
        Button("SIGN OUT") { signOut(ctx); onSignOut() }
    }
}

@Composable
private fun BuildScreen(activity: MainActivity, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx: Context = activity
    var preview by remember { mutableStateOf<Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val progress by Bridge.progress.collectAsState()
    LaunchedEffect(Unit) {
        val (p, e) = Bridge.preview(ctx)
        preview = p
        error = e
    }
    DisposableEffect(building) {
        activity.keepAwake(building)
        onDispose { activity.keepAwake(false) }
    }
    val main = Settings.mainBookName(ctx)
    val light = Settings.lightBookName(ctx)
    Page("Build") {
        val p = preview
        when {
            building -> {
                Para(progress ?: "Starting…")
                Para("This takes a few minutes the first time. Keep the app open; if it stops, open it and build again to carry on.")
            }
            error != null -> {
                Para(error!!)
                Button("BACK", onBack)
            }
            p == null -> Para("Looking at your contacts…")
            else -> {
                Label(main.uppercase())
                Para("${p.people} people. ${p.noNumber} have no phone number and stay out of $light and LightOS.")
                Label("LIGHTOS NOW")
                Para("${p.lightOsNow} entries.${if (p.fromLightOs > 0) " ${p.fromLightOs} ${if (p.fromLightOs == 1) "person has" else "people have"} numbers $main doesn't, and will be added to it first." else ""}")
                Label(light.uppercase())
                Para("Will hold ${p.lightCards} entries, one per number.${if (p.lightStrangers > 0) " The ${p.lightStrangers} already in it will be removed." else ""}")
                Label("THEN")
                Para("LightOS's address book is made to match $light. Entries with the same number are reused and renamed, missing ones are added, and leftover duplicates are removed.")
                if (confirm) {
                    Para("Build now? Back up LightOS's contacts first if you haven't.")
                    Button("BUILD") {
                        building = true
                        Bridge.build(ctx) { e ->
                            building = false
                            if (e == null) onDone() else error = e
                        }
                    }
                    Button("CANCEL") { confirm = false }
                } else {
                    Button("BUILD") { confirm = true }
                    Button("BACK", onBack)
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(ctx: Context, onSignOut: () -> Unit) {
    var state by remember { mutableStateOf(State.load(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress by Bridge.progress.collectAsState()

    fun check() {
        busy = true
        error = null
        Bridge.check(ctx) { e ->
            error = e
            state = State.load(ctx)
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        BridgeWork.start(ctx)
        check()
    }

    val main = Settings.mainBookName(ctx)
    val light = Settings.lightBookName(ctx)
    Page("Rolodex Bridge") {
        Para("On. $light and LightOS follow $main, with one number per entry. New contacts made in LightOS are added to $main.")
        Label("NOW")
        Para("${state.people} people in $main · ${state.pieces.size} entries in $light and LightOS")
        Label("LAST CHECK")
        Para(
            (error ?: state.lastError)?.let { "Problem: $it" }
                ?: when {
                    state.lastRun == 0L -> "Not yet"
                    System.currentTimeMillis() - state.lastRun < 60_000 -> "Just now"
                    else -> DateUtils.getRelativeTimeSpanString(state.lastRun).toString()
                },
        )
        if (busy) Label(progress?.uppercase() ?: "CHECKING…") else Button("CHECK NOW") { check() }
        if (state.problems.isNotEmpty()) {
            Label("NEEDS A LOOK")
            state.problems.forEach { Para(it) }
        }
        Spacer(Modifier.height(designDp(12f)))
        Para("Edit and delete contacts in Rolodex Light. Changes made to these entries in LightOS aren't copied back, and are replaced when the contact changes in $main.")
        if (confirm) {
            Para("Stop the bridge and sign out? No contacts are deleted.")
            Button("STOP AND SIGN OUT") { signOut(ctx); onSignOut() }
            Button("CANCEL") { confirm = false }
        } else if (!busy) Button("STOP AND SIGN OUT") { confirm = true }
    }
}

private fun signOut(ctx: Context) {
    BridgeWork.stop(ctx)
    Settings.signOut(ctx)
    State.clear(ctx)
}

/** "Contacts (c124fe4b-… fw)" → "Contacts". */
private fun shortName(b: PhoneBook): String = b.name.substringBefore(" (").ifBlank { b.name }

// ---- LightOS-style pieces ----

@Composable
private fun Page(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(24f), vertical = designDp(16f)),
    ) {
        BasicText(title, style = headingStyle())
        Spacer(Modifier.height(designDp(12f)))
        content()
        Spacer(Modifier.height(designDp(40f)))
    }
}

@Composable
private fun Para(text: String) {
    BasicText(text, style = paragraphStyle(), modifier = Modifier.padding(vertical = designDp(6f)))
}

@Composable
private fun Label(text: String) {
    BasicText(text, style = labelStyle(), modifier = Modifier.padding(top = designDp(10f), bottom = designDp(2f)))
}

@Composable
private fun Button(text: String, onClick: () -> Unit) {
    BasicText(text, style = buttonStyle(), modifier = Modifier.fillMaxWidth().flatClickable(onClick).padding(vertical = designDp(12f)))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    BasicText((if (selected) "● " else "○ ") + text, style = paragraphStyle(), modifier = Modifier.fillMaxWidth().flatClickable(onClick).padding(vertical = designDp(8f)))
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, secret: Boolean = false, onChange: (String) -> Unit) {
    Label(label)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = paragraphStyle(),
        cursorBrush = SolidColor(LightStyle.content),
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = designDp(4f)),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
}
