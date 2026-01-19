package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.network.helper.UnsafeOkHttpClient
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.WebDavStorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.sardine.DavResource
import com.xyoye.sardine.impl.OkHttpSardine
import com.xyoye.sardine.util.SardineConfig
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Date

/**
 * Modified:
 * 1. 强制将账号密码注入播放 URL (http://user:pass@host/...) 以解决webdav鉴权丢失问题
 * 2. 伪装 User-Agent
 */
class WebDavStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library) {

    private val sardine: OkHttpSardine by lazy {
        OkHttpSardine(UnsafeOkHttpClient.client)
    }

    init {
        SardineConfig.isXmlStrictMode = this.library.webDavStrict
        // 依然注册到全局，作为双重保险
        if (!library.account.isNullOrEmpty() && !library.password.isNullOrEmpty()) {
            UnsafeOkHttpClient.registerCredentials(
                library.url,
                library.account!!,
                library.password!!
            )
        }
    }

    override suspend fun getRootFile(): StorageFile {
        val rootPath = Uri.parse(library.url).path ?: "/"
        return pathFile(rootPath, true)
    }

    override suspend fun openFile(file: StorageFile): InputStream? {
        return try {
            sardine.get(file.fileUrl())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        return try {
            sardine.list(file.fileUrl())
                .filter { isChildFile(file.fileUrl(), it.href) }
                .map { WebDavStorageFile(it, this) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun pathFile(path: String, isDirectory: Boolean): StorageFile {
        val hrefUrl = resolvePath(path).toString()
        val davResource = CustomDavResource(hrefUrl)
        return WebDavStorageFile(davResource, this)
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? {
        val storagePath = history.storagePath ?: return null
        return pathFile(storagePath, false).also {
            it.playHistory = history
        }
    }

    // 👇👇👇 核心修改：暴力注入账号密码到 URL 👇👇👇
    override suspend fun createPlayUrl(file: StorageFile): String {
        val originalUrl = file.fileUrl()
        val account = library.account
        val password = library.password

        if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
            try {
                val uri = URI(originalUrl)
                // 对账号密码进行 URL 编码，防止特殊字符报错
                val encodedUser = URLEncoder.encode(account, "UTF-8")
                val encodedPass = URLEncoder.encode(password, "UTF-8")

                // 拼接成 http://user:pass@host:port/path 格式
                val userInfo = "$encodedUser:$encodedPass"

                // 重建 URL
                val newUrl = URI(
                    uri.scheme,
                    userInfo, // 这里注入 userInfo
                    uri.host,
                    uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment
                ).toString()

                return newUrl
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return originalUrl
    }

    // 👇 返回伪装的 User-Agent，防止 webdav 认为是不安全的爬虫而断开连接
    override fun getNetworkHeaders(): Map<String, String>? {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
            "Connection" to "close" // 强制短连接，避免 webdav 的鉴权状态错乱
        )
    }

    override suspend fun test(): Boolean {
        return try {
            if (!library.account.isNullOrEmpty()) {
                UnsafeOkHttpClient.registerCredentials(library.url, library.account!!, library.password!!)
            }
            sardine.list(getRootFile().fileUrl())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            ToastCenter.showError("连接失败: ${e.message}")
            false
        }
    }

    private fun getAccountInfo(): Pair<String, String>? {
        if (library.account.isNullOrEmpty()) {
            return null
        }
        return Pair(library.account ?: "", library.password ?: "")
    }

    private fun isChildFile(parent: String, child: URI): Boolean {
        try {
            val parentPath = URI(parent).path
            val childPath = child.path
            return parentPath != childPath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private class CustomDavResource(href: String, isDirectory: Boolean = true) : DavResource(
        href,
        Date(),
        Date(),
        if (isDirectory) "httpd/unix-directory" else "application/octet-stream",
        0,
        "",
        "",
        emptyList(),
        "",
        emptyList(),
        emptyMap()
    )
}