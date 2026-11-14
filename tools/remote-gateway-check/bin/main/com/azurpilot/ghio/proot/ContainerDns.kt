package com.azurpilot.ghio.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 容器 DNS 供给：DoH 主路由预解析，阿里 UDP 53 兜底（#31）。
 *
 * 部分校园网/企业网丢弃发往公共 DNS 的 UDP 53，而容器内 glibc 只认 resolv.conf
 * 里的 53 端口 nameserver，App 进程又无权绑 53 做本地转发（该端口还常被 VPN 类
 * 应用占用）。「DoH 优先」因此落地为：会话启动前，把热更新 Git 源、镜像加速、
 * 旧运行时前端下载基址与 NTP 等已知关键域名经 DoH（443 端口，不受 UDP 53 拦截
 * 影响）解析，写入容器 /etc/hosts 受管块；其余域名继续走 resolv.conf 的阿里
 * DNS 兜底（[ProotHost.writeResolvConf]）。
 *
 * 受管块每次会话启动整块重写，陈旧 IP 的存活期不超过一次会话；解析全程限时、
 * 逐项容错，任何失败只降级为「无预解析」（回到阿里兜底），绝不阻塞启动。
 *
 * Container DNS provisioning: DoH-first pre-resolution with Ali UDP 53 as the
 * fallback (issue #31).
 *
 * Some campus/enterprise networks drop UDP 53 toward public DNS servers, while
 * the guest's glibc only understands port-53 nameservers from resolv.conf, and
 * the app process may not bind port 53 for a local forwarder (the port is also
 * often occupied by VPN apps). "DoH first" is therefore implemented by
 * pre-resolving the known-critical hostnames (hot-update Git sources, mirror
 * prefixes, the legacy frontend download base, and NTP servers) over DoH
 * (port 443, immune to UDP 53 filtering) into a managed block of the guest
 * /etc/hosts before each session; everything else keeps falling back to the Ali
 * DNS in resolv.conf ([ProotHost.writeResolvConf]).
 *
 * The managed block is rewritten wholesale at every session start, so a stale
 * address lives no longer than one session; resolution is time-boxed and
 * tolerant per entry — any failure merely degrades to "no pre-resolution"
 * (Ali fallback) and never blocks startup.
 */
class ContainerDns {

    /** DoH 专用客户端：超时收紧到亚秒级，坏网络下也不拖慢会话启动 / DoH-dedicated client: sub-second-ish timeouts so a dead network never slows session startup. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** 阿里 DoH 在前、DNSPod 兜底；URL 用 IP 字面量，无自举解析依赖 / Ali DoH first with DNSPod fallback; IP-literal URLs need no bootstrap resolution. */
    private val resolvers: List<DnsOverHttps> by lazy {
        DOH_URLS.map { DnsOverHttps.Builder().client(http).url(it.toHttpUrl()).includeIPv6(false).build() }
    }

    /**
     * 会话启动前重写容器 /etc/hosts 受管块：先清旧块，再写入本轮 DoH 解析结果。
     *
     * Rewrites the managed block of the guest /etc/hosts before a session
     * starts: the previous block is stripped first, then this round's DoH
     * results are written in.
     */
    suspend fun provision(rootfsDir: File, mirrorPrefix: String, distributionBase: String) {
        runCatching {
            val hosts = criticalHosts(rootfsDir, mirrorPrefix, distributionBase)
            val resolved = resolveConcurrently(hosts)
            writeManagedBlock(File(rootfsDir, "etc/hosts"), resolved)
            Timber.i(
                "container dns: DoH pinned %d/%d hosts [%s]",
                resolved.size, hosts.size, resolved.keys.sorted().joinToString(),
            )
        }.onFailure { Timber.w(it, "container dns provision failed") }
    }

    /**
     * 汇总需要预解析的域名：部署清单里的更新源、镜像前缀、旧运行时前端下载
     * 基址，加热更回退源与 NTP 静态集；去重后上限 [MAX_HOSTS] 个。
     *
     * Collects the hostnames worth pre-resolving: update sources from the
     * deployed manifest, the mirror prefix, the legacy frontend download base,
     * plus the static hot-update fallbacks and NTP servers; deduplicated and
     * capped at [MAX_HOSTS].
     */
    private fun criticalHosts(rootfsDir: File, mirrorPrefix: String, distributionBase: String): List<String> {
        val manifest = runCatching {
            Json.parseToJsonElement(File(rootfsDir, MANIFEST_REL).readText()).jsonObject
        }.getOrNull()
        val urls = listOf("azurpilot_update_repo", "azurpilot_repo").mapNotNull { key ->
            runCatching { manifest?.get(key)?.jsonPrimitive?.content }.getOrNull()
        }
        return (urls + mirrorPrefix + distributionBase + STATIC_HOSTS)
            .mapNotNull(::hostOf)
            .distinct()
            .take(MAX_HOSTS)
    }

    /**
     * 逐域名并发解析，先阿里后 DNSPod；总预算 [RESOLVE_BUDGET_MS] 兜底，超时
     * 保留已完成的部分结果。
     *
     * Resolves hostnames concurrently, Ali before DNSPod per host; the overall
     * [RESOLVE_BUDGET_MS] budget keeps partial results when it fires.
     */
    private suspend fun resolveConcurrently(hosts: List<String>): Map<String, String> {
        if (hosts.isEmpty()) return emptyMap()
        val resolved = ConcurrentHashMap<String, String>()
        withTimeoutOrNull(RESOLVE_BUDGET_MS) {
            coroutineScope {
                hosts.forEach { host ->
                    launch(Dispatchers.IO) {
                        resolveOne(host)?.let { resolved[host] = it }
                    }
                }
            }
        } ?: Timber.w("container dns: DoH budget %dms exceeded, keeping %d entries", RESOLVE_BUDGET_MS, resolved.size)
        return resolved
    }

    /** 单域名顺序尝试各家 DoH，全败返回 null / Tries each DoH provider in order; null when all fail. */
    private fun resolveOne(host: String): String? {
        for (resolver in resolvers) {
            try {
                return resolver.lookup(host).firstOrNull()?.hostAddress ?: continue
            } catch (error: Exception) {
                Timber.d(error, "container dns: %s via %s failed", host, resolver.url.host)
            }
        }
        return null
    }

    /**
     * 重写 /etc/hosts 受管块：剥掉历史块（含残留的多个旧块）后追加本轮结果；
     * 结果为空时仅剥离，避免陈旧 IP 越期滞留。
     *
     * Rewrites the managed block of /etc/hosts: strips every historical block
     * (including leftover duplicates) and appends this round's entries; with no
     * results only the strip happens, so stale addresses never outlive their
     * session.
     */
    private fun writeManagedBlock(hostsFile: File, resolved: Map<String, String>) {
        val existing = if (hostsFile.isFile) hostsFile.readText() else ""
        val base = existing.replace(MANAGED_BLOCK, "").trimEnd()
        val content = if (resolved.isEmpty()) {
            base
        } else {
            val entries = resolved.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.value} ${it.key}" }
            val block = "$BEGIN_MARK\n$entries\n$END_MARK\n"
            if (base.isEmpty()) block else "$base\n\n$block"
        }
        // 内容为空且文件本就不存在才跳过；否则空结果也要落盘，把旧受管块真正剥掉
        if (content.isEmpty() && !hostsFile.isFile) return
        if (content == existing) return
        hostsFile.parentFile?.mkdirs()
        hostsFile.writeText(content)
    }

    /** 从 URL 提取小写主机名；无主机名/localhost/非域名一律丢弃 / Extracts the lowercase hostname; entries without one, localhost, or non-domains are dropped. */
    private fun hostOf(raw: String): String? = runCatching { URI(raw.trim()).host }
        .getOrNull()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() && it != "localhost" && '.' in it }

    private companion object {

        /** DoH 服务端：阿里 223.5.5.5 主、DNSPod 120.53.53.53 备，均支持 RFC 8484 / DoH endpoints: Ali primary, DNSPod backup; both speak RFC 8484. */
        private val DOH_URLS = listOf("https://223.5.5.5/dns-query", "https://120.53.53.53/dns-query")

        private const val CONNECT_TIMEOUT_MS = 1_500L
        private const val CALL_TIMEOUT_MS = 2_500L

        /** 全部域名的解析总预算；命中即取消未完成项，保留已有结果 / The overall resolution budget; on expiry pending lookups are cancelled and finished ones kept. */
        private const val RESOLVE_BUDGET_MS = 6_000L

        /** 域名数上限，防清单异常拖长启动 / Host cap guarding startup against a runaway manifest. */
        private const val MAX_HOSTS = 12

        private const val MANIFEST_REL = "opt/azurpilot/BUILD_MANIFEST"

        private const val BEGIN_MARK = "# BEGIN azurpilot-doh (managed; rewritten every session start)"
        private const val END_MARK = "# END azurpilot-doh"

        /** 历史受管块（含潜在的多个残留块）整体剥除 / Strips any historical managed blocks, including leftover duplicates. */
        private val MANAGED_BLOCK =
            Regex(Regex.escape(BEGIN_MARK) + ".*" + Regex.escape(END_MARK) + "\\n?", RegexOption.DOT_MATCHES_ALL)

        /**
         * 静态关键域名：热更主/回退源与 NTP 校时（NTP 与 #28 的容器 DNS 故障
         * 同源，顺手覆盖）。容器内其余域名不在 DoH 主路由内，走 resolv.conf。
         *
         * Static critical hostnames: hot-update primary/fallback sources and
         * NTP servers (NTP shares the in-container DNS failure root of #28, so
         * it is covered here as well). Everything else stays on resolv.conf.
         */
        private val STATIC_HOSTS = listOf(
            "cnb.cool",
            "github.com",
            "ntp.aliyun.com",
            "ntp.tencent.com",
            "ntp.ntsc.ac.cn",
            "pool.ntp.org",
            "cn.pool.ntp.org",
        )
    }
}
