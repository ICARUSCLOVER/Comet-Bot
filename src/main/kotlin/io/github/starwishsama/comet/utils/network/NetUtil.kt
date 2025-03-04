package io.github.starwishsama.comet.utils.network

import cn.hutool.http.HttpException
import io.github.starwishsama.comet.BotVariables.cfg
import io.github.starwishsama.comet.BotVariables.daemonLogger
import io.github.starwishsama.comet.exceptions.ApiException
import io.github.starwishsama.comet.utils.StringUtil.containsEtc
import io.github.starwishsama.comet.utils.TaskUtil
import io.github.starwishsama.comet.utils.debugS
import io.github.starwishsama.comet.utils.network.NetUtil.proxyIsUsable
import okhttp3.*
import org.openqa.selenium.*
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.edge.EdgeDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import org.openqa.selenium.opera.OperaDriver
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.ui.ExpectedCondition
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.*
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

fun Response.isType(typeName: String): Boolean = headers()["content-type"]?.contains(typeName) == true

fun Socket.isUsable(timeout: Int = 1_000, isReloaded: Boolean = false): Boolean {
    if (proxyIsUsable == 0 || isReloaded) {
        try {
            val connection = (URL("https://www.gstatic.com/generate_204").openConnection(Proxy(cfg.proxyType, this.remoteSocketAddress))) as HttpURLConnection
            connection.connectTimeout = 2000
            connection.connect()
            proxyIsUsable = 1
        } catch (t: IOException) {
            proxyIsUsable = -1
        } finally {
            close()
        }
    }
    return inetAddress.isReachable(timeout)
}

object NetUtil {
    var proxyIsUsable = 0
    lateinit var driver: WebDriver

    const val defaultUA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/85.0.4183.102 Safari/537.36"


    /**
     * 执行 Http 请求 (Get)
     *
     * 注意：响应需要使用 [Response.close] 关闭或使用 [use], 否则会导致泄漏
     *
     * 获取后的 [Response] 为异步, 响应完全读取 (如使用 [Response.body] 获取响应体字符串)后会失效
     *
     * 并且**不可再使用**, 否则抛出 [IllegalStateException]
     *
     * @param url 请求的地址
     * @param timeout 超时时间, 单位为秒
     * @param proxyUrl 代理地址 (如果需要使用的话)
     * @param proxyPort 代理端口 (如果需要使用的话)
     * @param action 执行请求前的额外操作, 如添加 header 等. 详见 [Request.Builder]
     */
    fun executeRequest(url: String,
                       timeout: Long = 2,
                       proxyUrl: String = cfg.proxyUrl,
                       proxyPort: Int = cfg.proxyPort,
                       action: Request.Builder.() -> Request.Builder = {
                               header("user-agent", defaultUA)
                           }
    ): Call {
        val builder = OkHttpClient().newBuilder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .followRedirects(true)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .hostnameVerifier { _, _ -> true }

        if (proxyIsUsable > 0 && proxyUrl.isNotBlank() && proxyPort > 0) {
            try {
                val socket = Socket(proxyUrl, proxyPort)
                if (socket.isUsable()) {
                    builder.proxy(Proxy(cfg.proxyType, Socket(proxyUrl, proxyPort).remoteSocketAddress))
                }
                daemonLogger.verbose("使用代理连接")
            } catch (e: Exception) {
                daemonLogger.verbose("无法连接到代理服务器, ${e.message}")
            }
        }

        val client = builder.build()
        val request = Request.Builder().url(url).action().build()
        return client.newCall(request)
    }

    /**
     * 执行 Http 请求 (Get)
     *
     * 注意：响应需要使用 [Response.close] 关闭或使用 [use], 否则会导致泄漏
     *
     * 获取后的 [Response] 为异步, 响应完全读取后会失效
     *
     * 并且**不可再使用**, 否则抛出 [IllegalStateException]
     *
     * 附上执行后响应会失效的方法：
     * [Response.close]
     * Response.body.close()
     * Response.body().source().close()
     * Response.body().charStream().close()
     * Response.body().byteString().close()
     * Response.body().bytes()
     * Response.body().string()
     *
     * @param url 请求的地址
     * @param timeout 超时时间, 单位为秒
     * @param proxyUrl 代理地址 (如果需要使用的话)
     * @param proxyPort 代理端口 (如果需要使用的话)
     * @param call 执行请求前的额外操作, 如添加 header 等. 详见 [Request.Builder]
     * @param autoClose 是否自动关闭 [ResponseBody], 适用于需要使用 bodyStream 等环境
     * @param autoCloseDelay 自动关闭 [ResponseBody] 的延迟秒数
     */
    fun executeHttpRequest(url: String,
                           timeout: Long = 2,
                           proxyUrl: String = cfg.proxyUrl,
                           proxyPort: Int = cfg.proxyPort,
                           call: Request.Builder.() -> Request.Builder = {
                               header("user-agent", defaultUA)
                           },
                           autoClose: Boolean = false,
                           autoCloseDelay: Long = 15
    ): Response {
        val startTime = System.nanoTime()

        var result: Response? = null

        try {
           result = executeRequest(url, timeout, proxyUrl, proxyPort, call).execute()
        } catch (e: IOException) {
            daemonLogger.warning("执行网络操作失败\n" + e.stackTraceToString())
        } finally {
            daemonLogger.debugS("执行网络操作用时 ${(System.nanoTime() - startTime).toDouble() / 1_000_000}ms")

            if (autoClose) {
                TaskUtil.runAsync(autoCloseDelay) {
                    if (result?.body() != null) {
                        result.close()
                    }
                }
            }
        }

        return result ?: throw ApiException("执行网络操作失败")
    }

    fun getPageContent(url: String, timeout: Long = 2): String? = executeHttpRequest(url, timeout, cfg.proxyUrl, cfg.proxyPort).body()?.string()

    /**
     * 下载文件
     *
     * @param address  下载地址
     * @param fileFolder 下载文件储存的文件夹
     * @param fileName 下载文件的名称
     */
    fun downloadFile(fileFolder: File, address: String, fileName: String): File {
        val file = File(fileFolder, fileName)
        val url = URL(address)
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode in 200..300) {
                val `in` = BufferedInputStream(conn.inputStream)
                if (!file.exists()) file.createNewFile()
                val fos = FileOutputStream(file)
                val bos = BufferedOutputStream(fos, 2048)
                val data = ByteArray(2048)
                var x: Int
                while (`in`.read(data, 0, 2048).also { x = it } >= 0) {
                    bos.write(data, 0, x)
                }
                bos.close()
                `in`.close()
                fos.close()
            } else {
                throw ApiException("在下载时发生了错误, 响应码 ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }

        return file
    }

    fun isTimeout(t: Throwable): Boolean {
        val msg = t.message?.toLowerCase() ?: return false
        // FIXME: 这不是一个很好的识别方法
        return msg.containsEtc(false, "time", "out") || t.javaClass.simpleName.toLowerCase().contains("timeout")
    }

    @Throws(HttpException::class)
    fun checkPingValue(address: String = "https://www.gstatic.com/generate_204", timeout: Long = 2000): Long {
        val startTime = LocalDateTime.now()

        executeHttpRequest(address, timeout).use { conn ->
            return if (conn.isSuccessful) {
                Duration.between(startTime, LocalDateTime.now()).toMillis()
            } else {
                -1L
            }
        }
    }

    /**
     * 获取网页截图
     *
     * @param address 需截图的网页地址
     * @param extraExecute 执行的额外操作, 如执行脚本
     */
    fun getScreenshot(
            address: String, extraExecute: WebDriver.() -> Unit = {
                // 获取推文使用, 如有其他需求请自行重载
                val wait = WebDriverWait(this, 50, 1)

                // 等待推文加载完毕再截图
                wait.until(ExpectedCondition { webDriver ->
                    webDriver?.findElement(By.cssSelector("article"))
                    var tag: By? = null
                    try {
                        tag = By.tagName("img")
                    } catch (ignored: IllegalArgumentException) {
                        // 部分推文是没有图片的
                    }
                    tag?.let { webDriver?.findElement(it) }
                })

                // 执行脚本获取合适的推文宽度
                val jsExecutor = (this as JavascriptExecutor)
                val width =
                        jsExecutor.executeScript("""return document.querySelector("section").getBoundingClientRect().bottom""") as Double

                // 调整窗口大小
                manage().window().size = Dimension(640, width.toInt())
            }
    ): File? {
        try {
            if (!::driver.isInitialized) return null

            var isConnected = false

            // 检查驱动器是否正常, 如果不正常重新初始化
            try {
                driver.get(address)
                isConnected = true
            } catch (e: WebDriverException) {
                initDriver()
            }

            // 避免重新获取徒增时长
            if (isConnected) driver.get(address)

            driver.extraExecute()

            return (driver as TakesScreenshot).getScreenshotAs(OutputType.FILE)
        } catch (e: Exception) {
            daemonLogger.warning("在调用 WebDriver 时出现异常", e)
        }

        return null
    }

    fun initDriver() {
        if (!cfg.debugMode || cfg.webDriverName.isBlank()) return

        try {
            when (cfg.webDriverName.toLowerCase()) {
                "chrome" -> driver = ChromeDriver()
                "edge" -> driver = EdgeDriver()
                "firefox" -> driver = FirefoxDriver()
                "ie", "internetexplorer" -> driver = InternetExplorerDriver()
                "opera" -> driver = OperaDriver()
                "remote" -> driver = RemoteWebDriver(URL(cfg.remoteWebDriver), DesiredCapabilities.chrome())
                else -> {
                    if (cfg.webDriverName.isNotEmpty()) {
                        daemonLogger.warning("不支持的 WebDriver 类型: ${cfg.webDriverName}, Comet 支持 [Chrome, Edge, Firefox, IE, Opera]")
                    }
                }
            }
        } catch (e: RuntimeException) {
            daemonLogger.warning("在尝试加载 WebDriver for ${cfg.webDriverName} 时出现问题", e)
        }
    }

    fun closeDriver() {
        if (driverUsable()) driver.close()
    }

    fun driverUsable(): Boolean = ::driver.isInitialized
}