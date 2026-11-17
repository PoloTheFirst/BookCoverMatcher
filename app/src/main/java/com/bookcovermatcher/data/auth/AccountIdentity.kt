// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.auth

import com.bookcovermatcher.core.remote.SourceAccount
import com.bookcovermatcher.core.wishlist.MiniJson
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Narrow authenticated profile requests, with the already granted scopes. No redirects/logging. */
internal suspend fun readAccountIdentity(provider: String, token: String): SourceAccount? = withContext(Dispatchers.IO) {
    val endpoint = if (provider == "google")
        "https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress,displayName)"
    else "https://graph.microsoft.com/v1.0/me?\$select=id,mail,userPrincipalName,displayName"
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (connection.responseCode != 200) return@withContext null
        val body = connection.inputStream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (bytes.size() <= 65_536) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        if (body.size > 65_536) return@withContext null
        val json = MiniJson.parse(body.toString(Charsets.UTF_8)) as? Map<*, *> ?: return@withContext null
        val user = if (provider == "google") json["user"] as? Map<*, *> else json
        user ?: return@withContext null
        val key = (user[if (provider == "google") "permissionId" else "id"] as? String)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val label = listOf("emailAddress", "mail", "userPrincipalName", "displayName")
            .firstNotNullOfOrNull { (user[it] as? String)?.takeIf(String::isNotBlank) } ?: "Account name unavailable"
        SourceAccount("$provider:$key", label)
    } catch (_: java.io.IOException) { null }
    catch (_: IllegalArgumentException) { null }
    finally { connection.disconnect() }
}
