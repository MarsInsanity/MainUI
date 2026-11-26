@file:JvmName("FolderGrant")

package io.github.marsinsanity.mainui.tools

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.IBinder

/**
 * Gives apps lasting access to storage folders (Storage Access Framework tree grants) without
 * Android's folder picker. Run as root through app_process, like [KeyInject]:
 *
 *   offer <uid> <package> <component> <nonce> <tree uri>...
 *     Becomes the picker's app ([uid], [package]: it holds MANAGE_DOCUMENTS, so Android lets it
 *     grant any folder) and starts MainUI's service [component] once per folder, the folder
 *     granted with the start as the picker grants it. MainUI keeps it (FolderGrantService).
 *     (A service start, as an activity start from the background is refused.)
 *   persist <package> <tree uri>...
 *     Makes [package]'s grants of these folders lasting (takePersistableUriPermission for another
 *     app, which root may do). MainUI granted them just before (Context.grantUriPermission).
 *
 * Prints "ok <uri>" or "error <uri> <why>" per folder.
 */
private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

private fun service(name: String, stub: String): Any {
    val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
    return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
}

private fun offer(uid: Int, pkg: String, component: String, nonce: String, uris: List<String>) {
    android.system.Os.setgid(uid)
    android.system.Os.setuid(uid)
    val am = service("activity", "android.app.IActivityManager\$Stub")
    val start = am.javaClass.methods.first { it.name == "startService" }
    for (u in uris) runCatching {
        val intent = Intent().setComponent(ComponentName.unflattenFromString(component)).setData(Uri.parse(u))
            .putExtra("nonce", nonce)
            .addFlags(READ_WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        // startService(caller, intent, resolvedType, requireForeground, callingPackage[, featureId], userId)
        var strings = 0
        val args = start.parameterTypes.map { t ->
            when (t) {
                Intent::class.java -> intent
                String::class.java -> if (strings++ == 1) pkg else null
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        // (Null: not found; a package "!", "!!" or "?": not allowed, or MainUI not in front.)
        val name = start.invoke(am, *args.toTypedArray()) as ComponentName?
        println(if (name != null && name.packageName.first().isLetter()) "ok $u" else "error $u not started (${name?.packageName})")
    }.onFailure { println("error $u ${(it.cause ?: it).message}") }
}

private fun persist(pkg: String, uris: List<String>) {
    val ugm = service("uri_grants", "android.app.IUriGrantsManager\$Stub")
    val take = ugm.javaClass.getMethod("takePersistableUriPermission", Uri::class.java,
        Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
    for (u in uris) runCatching { take.invoke(ugm, Uri.parse(u), READ_WRITE, pkg, 0); println("ok $u") }
        .onFailure { println("error $u ${(it.cause ?: it).message}") }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "offer" -> offer(args[1].toInt(), args[2], args[3], args[4], args.drop(5))
        "persist" -> persist(args[1], args.drop(2))
        else -> println("usage: offer <uid> <package> <component> <nonce> <uri>... | persist <package> <uri>...")
    }
}
