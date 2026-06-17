package com.yl.aigg.ai_gg666

import org.json.JSONObject

/**
 * AI/脚本层使用的原生能力门面。
 *
 * 把进程管理、搜索、读写、冻结这些能力从 UI/Overlay 里抽出来，
 * 避免调用点散落在各处。
 */
object NativeToolApi {
    private var appContext: android.content.Context? = null

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        MemoryEngine.setContext(context.applicationContext)
    }

    private fun ensureAttached(): String? {
        return if (MemoryEngine.getAttachedPid() == null) {
            "❌ 未附加游戏进程，请先附加"
        } else {
            null
        }
    }

    fun listProcesses(): String {
        val ctx = appContext ?: return "❌ NativeToolApi 尚未初始化"
        val processes = ProcessManager.getProcessList(ctx, includeSystem = false)
        if (processes.isEmpty()) return "当前未发现可附加进程"

        val sb = StringBuilder("可附加进程列表：\n")
        for ((index, proc) in processes.take(50).withIndex()) {
            val name = proc["appName"] ?: proc["name"] ?: proc["packageName"] ?: "未知"
            val pkg = proc["packageName"] ?: ""
            val pid = proc["pid"] ?: ""
            sb.append("${index + 1}. $name | pid=$pid | $pkg\n")
        }
        if (processes.size > 50) {
            sb.append("... 共 ${processes.size} 个进程")
        }
        return sb.toString().trim()
    }

    fun attachProcess(args: JSONObject): String {
        val pid = when {
            args.has("pid") -> args.optInt("pid", -1)
            else -> -1
        }
        if (pid <= 0) return "❌ attach_process 需要有效的 pid"

        val success = MemoryEngine.attachProcess(pid)
        if (!success) return "❌ 附加失败，请确认 Root 权限、目标进程状态和可访问内存段"

        return "✅ 已附加进程 pid=$pid"
    }

    fun getAttachedProcessInfo(): String {
        val pid = MemoryEngine.getAttachedPid() ?: return "当前未附加任何进程"
        return "当前已附加进程 pid=$pid"
    }

    fun searchMemory(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val mode = args.optString("mode", "exact")
        val value = args.optString("value", "")
        val type = args.optString("type", "dword")
        val limit = args.optInt("limit", 50).coerceIn(1, 100)

        return when (mode) {
            "exact" -> {
                val numVal: Any = if (type == "float" || type == "double") {
                    value.toDoubleOrNull() ?: return "❌ 无效数值: $value"
                } else {
                    value.toLongOrNull() ?: return "❌ 无效数值: $value"
                }
                formatSearchResults(MemoryEngine.searchExact(numVal, type), limit, withMachineCode = true)
            }
            "range" -> {
                val parts = value.split(",")
                if (parts.size != 2) return "❌ 范围格式错误，应为 '最小值,最大值'"
                val lo = parts[0].trim().toLongOrNull() ?: return "❌ 无效最小值: ${parts[0]}"
                val hi = parts[1].trim().toLongOrNull() ?: return "❌ 无效最大值: ${parts[1]}"
                formatSearchResults(MemoryEngine.searchByRange(lo, hi, type), limit)
            }
            "aob" -> formatSearchResults(MemoryEngine.searchAob(value), limit)
            else -> "❌ 未知搜索模式: $mode"
        }
    }

    fun filterMemoryResults(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val value = args.optString("value", "")
        val type = args.optString("type", "dword")
        val addressesArray = args.optJSONArray("addresses")
            ?: return "❌ filter_memory_results 需要 addresses 数组"
        val addresses = mutableListOf<Long>()
        for (i in 0 until addressesArray.length()) {
            val raw = addressesArray.opt(i)
            val addr = when (raw) {
                is Number -> raw.toLong()
                is String -> parseAddress(raw)
                else -> null
            } ?: continue
            addresses.add(addr)
        }
        if (addresses.isEmpty()) return "❌ 没有有效地址可过滤"

        val targetValue: Any = if (type == "float" || type == "double") {
            value.toDoubleOrNull() ?: return "❌ 无效数值: $value"
        } else {
            value.toLongOrNull() ?: return "❌ 无效数值: $value"
        }

        val results = MemoryEngine.filterResults(addresses, targetValue, type)
        return formatSearchResults(results, 50, withMachineCode = true)
    }

    fun readMemory(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val address = args.optString("address", "")
        val type = args.optString("type", "dword")
        val addrLong = parseAddress(address) ?: return "❌ 无效地址: $address"
        val value = MemoryEngine.readMemory(addrLong, type)
        return "地址 $address 的值: $value (类型: $type)"
    }

    fun writeMemory(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val address = args.optString("address", "")
        val value = args.optString("value", "")
        val type = args.optString("type", "dword")
        val addrLong = parseAddress(address) ?: return "❌ 无效地址: $address"
        val numVal: Any = if (type == "float" || type == "double") {
            value.toDoubleOrNull() ?: return "❌ 无效写入值: $value"
        } else {
            value.toLongOrNull() ?: return "❌ 无效写入值: $value"
        }

        val success = MemoryEngine.writeMemory(addrLong, numVal, type)
        return if (success) {
            val readBack = MemoryEngine.readMemory(addrLong, type)
            "✅ 已写入 $value 到地址 $address，回读验证: $readBack"
        } else {
            "❌ 写入失败，可能是地址不可写或权限不足"
        }
    }

    fun freezeMemory(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val address = args.optString("address", "")
        val value = args.optString("value", "")
        val type = args.optString("type", "dword")
        val addrLong = parseAddress(address) ?: return "❌ 无效地址: $address"
        val numVal: Any = if (type == "float" || type == "double") {
            value.toDoubleOrNull() ?: return "❌ 无效冻结值: $value"
        } else {
            value.toLongOrNull() ?: return "❌ 无效冻结值: $value"
        }

        return if (MemoryFreezer.freeze(addrLong, numVal, type)) {
            "✅ 已冻结地址 $address 为 $value ($type)"
        } else {
            "❌ 冻结失败"
        }
    }

    fun unfreezeMemory(args: JSONObject): String {
        val address = args.optString("address", "")
        val addrLong = parseAddress(address) ?: return "❌ 无效地址: $address"
        return if (MemoryFreezer.unfreeze(addrLong)) {
            "✅ 已解除冻结 $address"
        } else {
            "❌ 解除冻结失败"
        }
    }

    fun listFrozenMemory(): String {
        val frozen = MemoryFreezer.getFrozenAddresses()
        if (frozen.isEmpty()) return "当前没有冻结地址"

        val sb = StringBuilder("当前冻结列表：\n")
        for ((index, item) in frozen.withIndex()) {
            sb.append("${index + 1}. ${item["address"]} = ${item["value"]} (${item["type"]})\n")
        }
        return sb.toString().trim()
    }

    fun analyzeMemoryRegion(args: JSONObject): String {
        ensureAttached()?.let { return it }

        val address = args.optString("address", "")
        val range = args.optInt("range", 256).coerceIn(16, 4096)
        val addrLong = parseAddress(address) ?: return "❌ 无效地址: $address"
        val result = MemoryEngine.analyzeMemoryRegion(addrLong, range)
        if (result.isEmpty()) return "❌ 内存区域分析失败"
        return buildString {
            appendLine("地址分析结果：")
            appendLine("address=${result["address"]}")
            appendLine("range=${result["range"]}")
            appendLine("size=${result["size"]}")
            append("data=${result["data"]}")
        }
    }

    private fun parseAddress(address: String): Long? {
        val cleaned = address.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.startsWith("0x", ignoreCase = true)) {
            cleaned.substring(2).toLongOrNull(16)
        } else {
            cleaned.toLongOrNull() ?: cleaned.toLongOrNull(16)
        }
    }

    private fun formatSearchResults(
        results: List<Map<String, Any>>,
        limit: Int,
        withMachineCode: Boolean = false
    ): String {
        if (results.isEmpty()) return "搜索完成，未找到结果"

        val sb = StringBuilder("找到 ${results.size} 个结果：\n")
        for ((index, item) in results.take(limit).withIndex()) {
            val address = item["address"] ?: ""
            val value = item["value"] ?: ""
            val machineCode = item["machineCode"] ?: ""
            sb.append("${index + 1}. $address")
            if (withMachineCode && machineCode.toString().isNotBlank()) {
                sb.append(" [$machineCode]")
            }
            sb.append(" = $value\n")
        }
        if (results.size > limit) {
            sb.append("... 共 ${results.size} 个结果")
        }
        return sb.toString().trim()
    }
}
