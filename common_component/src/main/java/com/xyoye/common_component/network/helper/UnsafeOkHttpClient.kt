package com.xyoye.common_component.network.helper

import android.annotation.SuppressLint
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.xyoye.common_component.BuildConfig
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.PasswordAuthentication
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Modified: 增加 Cookie 管理和 UA 伪装，模拟 PC 浏览器行为
 */
object UnsafeOkHttpClient {

    private val credentialsMap = ConcurrentHashMap<String, Pair<String, String>>()
    private val authCache = ConcurrentHashMap<String, CachingAuthenticator>()

    // 增加 Cookie 存储，解决某些 WebDAV 服务验证一次后依赖 Cookie 保持会话的问题
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    init {
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val requestingHost = requestingHost
                // 只要有密码，不管 Host 对不对，都试一下（解决 Nascab IP 变动问题）
                if (credentialsMap.isNotEmpty()) {
                    // 优先取匹配的
                    var creds = credentialsMap[requestingHost]
                    // 没匹配到就取第一个（假设用户只连了一个服务）
                    if (creds == null && credentialsMap.isNotEmpty()) {
                        creds = credentialsMap.values.first()
                    }
                    if (creds != null) {
                        return PasswordAuthentication(creds.first, creds.second.toCharArray())
                    }
                }
                return super.getPasswordAuthentication()
            }
        })
    }

    fun registerCredentials(url: String, user: String, pass: String) {
        try {
            val host = java.net.URI(url).host
            if (host != null) {
                credentialsMap[host] = Pair(user, pass)
                authCache.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val dynamicAuthenticator = Authenticator { route, response ->
        val host = route?.address?.url?.host ?: response.request.url.host
        // 同样采用宽容匹配策略
        var creds = credentialsMap[host]
        if (creds == null && credentialsMap.isNotEmpty()) {
            creds = credentialsMap.values.first()
        }

        if (creds != null) {
            val digestAuth = DigestAuthenticator(Credentials(creds.first, creds.second))
            val authenticator = CachingAuthenticatorDecorator(digestAuth, authCache)
            return@Authenticator authenticator.authenticate(route, response)
        }
        null
    }

    private val unSafeTrustManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(unSafeTrustManager), null)
    }

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, unSafeTrustManager)
            .hostnameVerifier { _, _ -> true }
            .authenticator(dynamicAuthenticator)
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            // 👇 自动管理 Cookie
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            // 👇 伪装成 PotPlayer/浏览器，防止被服务器针对
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", "PotPlayer/230523") // 伪装！
                    .header("Connection", "close") // 关闭 Keep-Alive，强迫每次都重新验证，解决 Range 只有声音没画面问题
                    .build()
                chain.proceed(request)
            }

        if (BuildConfig.DEBUG) {
            builder.addNetworkInterceptor(LoggerInterceptor().webDav())
        }
        return@lazy builder.build()
    }
}