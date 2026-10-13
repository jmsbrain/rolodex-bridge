package com.jmsbrain.rolodexbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.compose.runtime.LaunchedEffect
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
}

private enum class Step { SignIn, Choose, Running }

@Composable
private fun BridgeApp(ctx: Context) {
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
        Step.Choose -> ChooseScreen(ctx, books, onStarted = { step = Step.Running }, onSignOut = { step = Step.SignIn })
        Step.Running -> StatusScreen(ctx, onSignOut = { step = Step.SignIn })
    }
}

@Composable
private fun AccessScreen(onAllow: () -> Unit) = Page("Rolodex Bridge") {
    Para("Copies new contacts between LightOS's address book and your mailbox.org address book, so Rolodex Light sees what you add in LightOS, and LightOS sees what you add in Rolodex Light.")
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
private fun ChooseScreen(ctx: Context, found: List<BookInfo>, onStarted: () -> Unit, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var serverBooks by remember { mutableStateOf(found.filter { !it.readOnly }) }
    var phoneBooks by remember { mutableStateOf<List<PhoneBookInfo>>(emptyList()) }
    var serverBook by remember { mutableStateOf(Settings.serverBook(ctx)) }
    var phoneBook by remember { mutableStateOf(Settings.phoneBook(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (serverBooks.isEmpty()) runCatching {
                serverBooks = CardDav(Settings.user(ctx)!!, Settings.password(ctx)!!).addressBooks(Settings.home(ctx)!!).filter { !it.readOnly }
            }.onFailure { error = it.message }
            phoneBooks = PhoneContacts(ctx.contentResolver).books()
        }
        if (serverBook == null) serverBook = (serverBooks.firstOrNull { it.name.equals("Clean Contacts", true) } ?: serverBooks.firstOrNull())?.url
        if (phoneBook == null) phoneBook = phoneBooks.firstOrNull()?.book
    }
    Page("Address books") {
        Label("MAILBOX.ORG")
        serverBooks.forEach { b -> Choice(b.name, b.url == serverBook) { serverBook = b.url } }
        Spacer(Modifier.height(16.dp))
        Label("LIGHTOS'S ADDRESS BOOK")
        Para("Where LightOS saves new contacts. The first one listed holds the newest contact.")
        phoneBooks.forEach { b -> Choice("${shortName(b.book)} · ${b.count} contacts", b.book == phoneBook) { phoneBook = b.book } }
        error?.let { Para(it) }
        Spacer(Modifier.height(16.dp))
        val sb = serverBooks.firstOrNull { it.url == serverBook }
        val pb = phoneBook
        if (busy) Label("STARTING…") else if (sb != null && pb != null) Button("START") {
            busy = true
            Settings.setServerBook(ctx, sb.url, sb.name)
            Settings.setPhoneBook(ctx, pb)
            scope.launch {
                val e = Bridge.start(ctx)
                busy = false
                if (e != null) error = e else {
                    BridgeWork.start(ctx)
                    onStarted()
                }
            }
        }
        Para("From now on, contacts added on either side are copied to the other. Contacts already there are left alone; the status screen can copy any that are missing.")
        Button("SIGN OUT") { signOut(ctx); onSignOut() }
    }
}

@Composable
private fun StatusScreen(ctx: Context, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(State.load(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf<Missing?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun work(label: String, job: suspend () -> String?) {
        busy = label
        error = null
        scope.launch {
            error = job()
            state = State.load(ctx)
            busy = null
        }
    }

    LaunchedEffect(Unit) {
        BridgeWork.start(ctx)
        work("CHECKING…") { Bridge.run(ctx, full = true) }
    }

    Page("Rolodex Bridge") {
        Para("On. New contacts in LightOS go to ${Settings.serverBookName(ctx)} on mailbox.org, and new ones there come to LightOS.")
        Label("SO FAR")
        Para("${state.sentToServer} sent to mailbox.org · ${state.addedToPhone} added to LightOS")
        Label("LAST CHECK")
        Para(
            (error ?: state.lastError)?.let { "Problem: $it" }
                ?: when {
                    state.lastRun == 0L -> "Not yet"
                    System.currentTimeMillis() - state.lastRun < 60_000 -> "Just now"
                    else -> DateUtils.getRelativeTimeSpanString(state.lastRun).toString()
                },
        )
        busy?.let { Label(it) } ?: Button("CHECK NOW") { work("CHECKING…") { Bridge.run(ctx, full = true) } }

        Spacer(Modifier.height(16.dp))
        Label("ONE-TIME COPY")
        val m = missing
        when {
            busy != null -> Unit
            confirm == "phone" && m != null -> {
                Para("Add ${contacts(m.toPhone)} from mailbox.org to LightOS's address book?")
                Button("ADD") { confirm = null; work("COPYING…") { Bridge.copyAllToPhone(ctx).also { missing = null } } }
                Button("CANCEL") { confirm = null }
            }
            confirm == "server" && m != null -> {
                Para("Add ${contacts(m.toServer)} from LightOS to ${Settings.serverBookName(ctx)}? Rolodex Light will get them too.")
                Button("ADD") { confirm = null; work("COPYING…") { Bridge.copyAllToServer(ctx).also { missing = null } } }
                Button("CANCEL") { confirm = null }
            }
            m == null -> {
                Para("Finds contacts that are on one side but not the other, matched by name, phone number or email.")
                Button("FIND MISSING") {
                    work("LOOKING…") {
                        val (found, e) = Bridge.missing(ctx)
                        missing = found
                        e
                    }
                }
            }
            else -> {
                if (m.toPhone == 0 && m.toServer == 0) Para("Nothing missing. Both sides have the same people.")
                if (m.toPhone > 0) {
                    Para("${contacts(m.toPhone)} on mailbox.org ${if (m.toPhone == 1) "isn't" else "aren't"} in LightOS's address book, so calls and texts show only a number.")
                    Button("ADD ${m.toPhone} TO LIGHTOS") { confirm = "phone" }
                }
                if (m.toServer > 0) {
                    Para("${contacts(m.toServer)} in LightOS ${if (m.toServer == 1) "isn't" else "aren't"} on mailbox.org, so Rolodex Light doesn't have ${if (m.toServer == 1) "it" else "them"}.")
                    Button("ADD ${m.toServer} TO MAILBOX.ORG") { confirm = "server" }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Label("ADDRESS BOOKS")
        Para("mailbox.org: ${Settings.serverBookName(ctx)}")
        Para("LightOS: ${Settings.phoneBook(ctx)?.let { shortName(it) }.orEmpty()}")
        Para("Copies new contacts only. Edits and deletions aren't copied.")
        if (confirm == "signout") {
            Para("Stop the bridge and sign out? No contacts are deleted.")
            Button("STOP AND SIGN OUT") { signOut(ctx); onSignOut() }
            Button("CANCEL") { confirm = null }
        } else if (busy == null) Button("STOP AND SIGN OUT") { confirm = "signout" }
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

private fun contacts(n: Int) = if (n == 1) "1 contact" else "$n contacts"
