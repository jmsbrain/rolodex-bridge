package com.jmsbrain.rolodexbridge

import android.provider.ContactsContract.CommonDataKinds.Im
import java.util.Locale

/** Messaging app name or scheme → Android IM protocol and label, as in Rolodex. Null means SIP. */
internal fun imProtocol(name: String): Pair<Int, String?>? = when (name.lowercase(Locale.ROOT)) {
    "signal", "sgnl" -> Im.PROTOCOL_CUSTOM to "Signal"
    "telegram", "tg" -> Im.PROTOCOL_CUSTOM to "Telegram"
    "whatsapp" -> Im.PROTOCOL_CUSTOM to "WhatsApp"
    "matrix" -> Im.PROTOCOL_CUSTOM to "Matrix"
    "xmpp", "jabber" -> Im.PROTOCOL_JABBER to null
    "skype", "skype-username" -> Im.PROTOCOL_SKYPE to null
    "aim" -> Im.PROTOCOL_AIM to null
    "msn", "msnim" -> Im.PROTOCOL_MSN to null
    "yahoo", "ymsgr" -> Im.PROTOCOL_YAHOO to null
    "qq" -> Im.PROTOCOL_QQ to null
    "gtalk", "google-talk" -> Im.PROTOCOL_GOOGLE_TALK to null
    "icq" -> Im.PROTOCOL_ICQ to null
    "sip" -> null
    else -> Im.PROTOCOL_CUSTOM to name.replaceFirstChar { it.uppercase() }
}

/** Digits plus the characters a dialer keeps. */
internal fun dialableNumber(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        val d = Character.digit(ch, 10)
        when {
            d >= 0 -> sb.append('0' + d)
            ch == '+' && sb.isEmpty() -> sb.append(ch)
            ch == '*' || ch == '#' || ch == ',' || ch == ';' -> sb.append(ch)
        }
    }
    return sb.toString()
}
