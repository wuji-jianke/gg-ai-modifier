package com.yl.aigg.ai_gg666

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 内存引擎 v2.0
 * - 持久化 Root Shell 会话（避免频繁 su 弹窗）
 * - 内存段智能筛选（优先 heap/anon，过滤只读段）
 * - 模糊搜索（>、<、==、!=、changed、unchanged）
 * - 特征码搜索（AOB Scan，支持重启后重定位）
 * - 分块读取（2MB chunk，避免 OOM）
 * - 数据对齐修复（Endian.little 显式声明）
 */
object MemoryEngine {

    private var attachedPid: Int? = null

    // 上一轮快照（用于模糊搜索对比）
    private var lastSnapshot: Map<Long, ByteArray> = emptyMap()

    // 特征码数据库（地址 -> 周围字节切片）
    private val aobDatabase = mutableMapOf<Long, AobSignature>()

    /**
     * 附加到目标进程
     */
    fun attachProcess(pid: Int): Boolean {
        return try {
            val procDir = File("/proc/$pid")
            if (!procDir.exists()) {
                val result = RootManager.executeRootCommand("ls /proc/$pid/status")
                if (result == null || result.isEmpty()) return false
            }
            attachedPid = pid
            lastSnapshot = emptyMap()
            aobDatabase.clear()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 分离当前进程
     */
    fun detachProcess() {
        attachedPid = null
        lastSnapshot = emptyMap()
        aobDatabase.clear()
    }

    /**
     * 获取当前附加的进程 PID
     */
    fun getAttachedPid(): Int? = attachedPid

    // ==================== 内存段智能筛选 ====================

    /**
     * 解析 /proc/pid/maps，返回按优先级排序的内存段
     * 优先级：[heap] > [anon:*] > rw-p 匿名 > 其他 rw-p
     * 过滤：跳过只读段 (r--)、跳过大于 50MB 的区域
     */
    fun getMemoryRegions(): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val mapsResult = RootManager.executeRootCommand("cat /proc/$pid/maps 2>/dev/null") ?: return emptyList()
            val regions = mutableListOf<MemoryRegionInfo>()

            for (line in mapsResult.lines()) {
                if (line.isBlank()) continue
                val parts = line.split(" ")
                if (parts.isEmpty()) continue

                val addrRange = parts[0].split("-")
                if (addrRange.size != 2) continue

                val startAddr = addrRange[0].toLongOrNull(16) ?: continue
                val endAddr = addrRange[1].toLongOrNull(16) ?: continue
                val permissions = if (parts.size > 1) parts[1] else "----"
                val name = if (parts.size > 5) parts.subList(5, parts.size).joinToString(" ") else ""

                val regionSize = endAddr - startAddr

                // 跳过太大的区域（> 50MB）和太小的区域
                if (regionSize > 50 * 1024 * 1024 || regionSize <= 0) continue

                // 只保留可读写的段
                if (!permissions.contains('r') || !permissions.contains('w')) continue

                // 计算优先级权重
                val priority = calculateRegionPriority(permissions, name)

                regions.add(MemoryRegionInfo(
                    startAddr = startAddr,
                    endAddr = endAddr,
                    permissions = permissions,
                    name = name,
                    priority = priority
                ))
            }

            // 按优先级排序（高优先级在前）
            regions.sortByDescending { it.priority }

            regions.map { region ->
                mapOf(
                    "startAddress" to region.startAddr.toInt(),
                    "endAddress" to region.endAddr.toInt(),
                    "size" to (region.endAddr - region.startAddr).toInt(),
                    "permissions" to region.permissions,
                    "isReadable" to region.permissions.contains('r'),
                    "isWritable" to region.permissions.contains('w'),
                    "isExecutable" to region.permissions.contains('x'),
                    "isAnonymous" to region.name.isEmpty(),
                    "name" to region.name,
                    "priority" to region.priority
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 计算内存段优先级
     * [heap] = 100, [anon:*] = 80, 匿名 rw-p = 60, 其他 rw-p = 40
     */
    private fun calculateRegionPriority(permissions: String, name: String): Int {
        var priority = 0

        // 基础权限分
        if (permissions.contains('r')) priority += 10
        if (permissions.contains('w')) priority += 20
        if (permissions.contains('x')) priority += 5  // 可执行段通常不包含游戏数据

        // 名称权重
        when {
            name.contains("[heap]") -> priority += 70
            name.contains("[anon:") -> priority += 50
            name.contains("[stack]") -> priority += 30
            name.isEmpty() -> priority += 40  // 匿名段
            name.contains(".so") -> priority += 10  // 共享库
            name.contains("[vdso]") -> priority -= 20  // 虚拟动态共享对象
        }

        return priority
    }

    /**
     * 获取高优先级的内存段（用于快速扫描）
     */
    private fun getHighPriorityRegions(): List<Pair<Long, Long>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val mapsResult = RootManager.executeRootCommand("cat /proc/$pid/maps 2>/dev/null") ?: return emptyList()
            val regions = mutableListOf<Triple<Long, Long, Int>>()

            for (line in mapsResult.lines()) {
                if (line.isBlank()) continue
                val parts = line.split(" ")
                if (parts.isEmpty()) continue

                val addrRange = parts[0].split("-")
                if (addrRange.size != 2) continue

                val startAddr = addrRange[0].toLongOrNull(16) ?: continue
                val endAddr = addrRange[1].toLongOrNull(16) ?: continue
                val permissions = if (parts.size > 1) parts[1] else "----"
                val name = if (parts.size > 5) parts.subList(5, parts.size).joinToString(" ") else ""

                val regionSize = endAddr - startAddr

                // 跳过太大的区域和太小的区域
                if (regionSize > 50 * 1024 * 1024 || regionSize <= 0) continue

                // 只保留可读写的段
                if (!permissions.contains('r') || !permissions.contains('w')) continue

                val priority = calculateRegionPriority(permissions, name)
                regions.add(Triple(startAddr, endAddr, priority))
            }

            // 按优先级排序，返回前 N 个高优先级段
            regions.sortByDescending { it.third }
            regions.take(20).map { Pair(it.first, it.second) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 精确搜索 ====================

    /**
     * 精确搜索内存
     * 使用分块读取（2MB chunk）避免 OOM
     */
    fun searchExact(value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val targetBytes = valueToBytes(value, type) ?: return emptyList()
            val results = mutableListOf<Map<String, Any>>()
            val regions = getHighPriorityRegions()

            for ((startAddr, endAddr) in regions) {
                val regionSize = endAddr - startAddr
                val chunkSize = 2 * 1024 * 1024L  // 2MB chunk

                var offset = 0L
                while (offset < regionSize) {
                    val currentChunkSize = minOf(chunkSize, regionSize - offset)
                    val chunkData = readMemoryChunk(pid, startAddr + offset, currentChunkSize.toInt()) ?: break

                    // 在 chunk 中搜索
                    val foundOffsets = searchInChunk(chunkData, targetBytes)
                    for (foundOffset in foundOffsets) {
                        val address = startAddr + offset + foundOffset
                        results.add(createResultMap(address, value, type))

                        if (results.size >= 500) break
                    }

                    if (results.size >= 500) break
                    offset += chunkSize - targetBytes.size + 1  // 重叠搜索避免边界遗漏
                }
                if (results.size >= 500) break
            }

            // 保存快照用于模糊搜索
            saveSnapshot(results, type)

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 范围搜索
     */
    fun searchByRange(minValue: Long, maxValue: Long, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val results = mutableListOf<Map<String, Any>>()
            val regions = getHighPriorityRegions()
            val typeSize = getTypeSize(type)

            for ((startAddr, endAddr) in regions) {
                val regionSize = endAddr - startAddr
                val chunkSize = 2 * 1024 * 1024L

                var offset = 0L
                while (offset < regionSize) {
                    val currentChunkSize = minOf(chunkSize, regionSize - offset)
                    val chunkData = readMemoryChunk(pid, startAddr + offset, currentChunkSize.toInt()) ?: break

                    // 按类型大小步进，检查每个位置的值是否在范围内
                    var pos = 0
                    while (pos + typeSize <= chunkData.size) {
                        val address = startAddr + offset + pos
                        val value = bytesToValue(chunkData.sliceArray(pos until pos + typeSize), type)
                        if (value != null) {
                            val longValue = when (value) {
                                is Float -> value.toLong()
                                is Double -> value.toLong()
                                else -> (value as? Number)?.toLong() ?: 0
                            }
                            if (longValue in minValue..maxValue) {
                                results.add(createResultMap(address, value, type))
                            }
                        }
                        pos += typeSize

                        if (results.size >= 500) break
                    }

                    if (results.size >= 500) break
                    offset += chunkSize
                }
                if (results.size >= 500) break
            }

            // 保存快照
            saveSnapshot(results, type)

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 在之前的结果中过滤
     */
    fun filterResults(previousAddresses: List<Int>, value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val results = mutableListOf<Map<String, Any>>()

            for (addr in previousAddresses) {
                val readValue = readMemory(addr, type)
                if (readValue != null && valuesEqual(readValue, value, type)) {
                    results.add(createResultMap(addr.toLong(), value, type))
                }
            }

            // 保存快照
            saveSnapshot(results, type)

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 模糊搜索 ====================

    /**
     * 模糊搜索（未知值搜索）
     * 支持：changed, unchanged, increased, decreased, greater, less, equal, not_equal
     */
    fun searchFuzzy(comparison: String, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        if (lastSnapshot.isEmpty()) {
            // 第一次搜索，保存当前状态作为快照
            val initialResults = searchAllValues(type)
            saveSnapshot(initialResults, type)
            return initialResults
        }

        return try {
            val results = mutableListOf<Map<String, Any>>()

            for ((address, oldBytes) in lastSnapshot) {
                val currentValue = readMemoryBytes(pid, address, oldBytes.size)
                if (currentValue == null) continue

                val oldVal = bytesToValue(oldBytes, type)
                val newVal = bytesToValue(currentValue, type)

                if (oldVal == null || newVal == null) continue

                val matches = when (comparison) {
                    "changed" -> !valuesEqual(oldVal, newVal, type)
                    "unchanged" -> valuesEqual(oldVal, newVal, type)
                    "increased" -> compareValues(newVal, oldVal, type) > 0
                    "decreased" -> compareValues(newVal, oldVal, type) < 0
                    "greater" -> compareValues(newVal, oldVal, type) > 0
                    "less" -> compareValues(newVal, oldVal, type) < 0
                    "equal" -> valuesEqual(oldVal, newVal, type)
                    "not_equal" -> !valuesEqual(oldVal, newVal, type)
                    else -> false
                }

                if (matches) {
                    results.add(createResultMap(address, newVal, type))
                }

                if (results.size >= 500) break
            }

            // 更新快照
            saveSnapshot(results, type)

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 搜索所有可读写区域的值（用于首次模糊搜索）
     */
    private fun searchAllValues(type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        val typeSize = getTypeSize(type)
        val results = mutableListOf<Map<String, Any>>()
        val regions = getHighPriorityRegions()

        for ((startAddr, endAddr) in regions) {
            val regionSize = endAddr - startAddr
            val chunkSize = 2 * 1024 * 1024L

            var offset = 0L
            while (offset < regionSize) {
                val currentChunkSize = minOf(chunkSize, regionSize - offset)
                val chunkData = readMemoryChunk(pid, startAddr + offset, currentChunkSize.toInt()) ?: break

                // 按类型大小步进，记录每个位置的值
                var pos = 0
                while (pos + typeSize <= chunkData.size) {
                    val address = startAddr + offset + pos
                    val value = bytesToValue(chunkData.sliceArray(pos until pos + typeSize), type)
                    if (value != null) {
                        results.add(createResultMap(address, value, type))
                    }
                    pos += typeSize

                    if (results.size >= 500) break
                }

                if (results.size >= 500) break
                offset += chunkSize
            }
            if (results.size >= 500) break
        }

        return results
    }

    // ==================== 特征码搜索 (AOB Scan) ====================

    /**
     * 特征码搜索
     * 记录目标地址前后的 16 字节切片，用于重启后重定位
     */
    fun searchAob(pattern: String, mask: String? = null): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val patternBytes = parseAobPattern(pattern)
            if (patternBytes.isEmpty()) return emptyList()

            val results = mutableListOf<Map<String, Any>>()
            val regions = getHighPriorityRegions()

            for ((startAddr, endAddr) in regions) {
                val regionSize = endAddr - startAddr
                val chunkSize = 2 * 1024 * 1024L

                var offset = 0L
                while (offset < regionSize) {
                    val currentChunkSize = minOf(chunkSize, regionSize - offset)
                    val chunkData = readMemoryChunk(pid, startAddr + offset, currentChunkSize.toInt()) ?: break

                    val foundOffsets = searchAobInChunk(chunkData, patternBytes, mask)
                    for (foundOffset in foundOffsets) {
                        val address = startAddr + offset + foundOffset

                        // 保存特征码签名（前后各 16 字节）
                        val signatureStart = maxOf(0, foundOffset - 16)
                        val signatureEnd = minOf(chunkData.size, foundOffset + patternBytes.size + 16)
                        val signatureBytes = chunkData.sliceArray(signatureStart until signatureEnd)

                        aobDatabase[address] = AobSignature(
                            address = address,
                            pattern = pattern,
                            contextBytes = signatureBytes,
                            contextOffset = foundOffset - signatureStart
                        )

                        results.add(mapOf(
                            "address" to "0x${address.toString(16).uppercase()}",
                            "addressInt" to address.toInt(),
                            "value" to pattern,
                            "type" to "aob",
                            "isFavorite" to false,
                            "isFrozen" to false
                        ))

                        if (results.size >= 500) break
                    }

                    if (results.size >= 500) break
                    offset += chunkSize - patternBytes.size + 1
                }
                if (results.size >= 500) break
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 重启后重定位（使用特征码数据库）
     */
    fun relocateAobSignatures(): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        if (aobDatabase.isEmpty()) return emptyList()

        return try {
            val results = mutableListOf<Map<String, Any>>()
            val regions = getHighPriorityRegions()

            for ((address, signature) in aobDatabase) {
                var found = false

                for ((startAddr, endAddr) in regions) {
                    val regionSize = endAddr - startAddr
                    val chunkSize = 2 * 1024 * 1024L

                    var offset = 0L
                    while (offset < regionSize) {
                        val currentChunkSize = minOf(chunkSize, regionSize - offset)
                        val chunkData = readMemoryChunk(pid, startAddr + offset, currentChunkSize.toInt()) ?: break

                        // 在 chunk 中搜索特征码上下文
                        val contextPattern = signature.contextBytes
                        val foundOffsets = searchInChunk(chunkData, contextPattern)

                        for (foundOffset in foundOffsets) {
                            val newAddress = startAddr + offset + foundOffset + signature.contextOffset

                            results.add(mapOf(
                                "address" to "0x${newAddress.toString(16).uppercase()}",
                                "addressInt" to newAddress.toInt(),
                                "value" to signature.pattern,
                                "type" to "aob",
                                "isFavorite" to false,
                                "isFrozen" to false,
                                "relocated" to true,
                                "oldAddress" to "0x${address.toString(16).uppercase()}"
                            ))

                            found = true
                            break
                        }

                        if (found) break
                        offset += chunkSize
                    }
                    if (found) break
                }
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 内存读写 ====================

    /**
     * 读取内存值
     */
    fun readMemory(address: Int, type: String): Any? {
        val pid = attachedPid ?: return null

        return try {
            val size = getTypeSize(type)
            val bytes = readMemoryBytes(pid, address.toLong(), size) ?: return null
            bytesToValue(bytes, type)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入内存值
     */
    fun writeMemory(address: Int, value: Any, type: String): Boolean {
        val pid = attachedPid ?: return false

        return try {
            val bytes = valueToBytes(value, type) ?: return false
            writeMemoryBytes(pid, address.toLong(), bytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 批量写入
     */
    fun writeBatch(requests: List<Map<String, Any>>): Boolean {
        var allSuccess = true
        for (req in requests) {
            val address = req["address"] as? Int ?: continue
            val value = req["value"] ?: continue
            val type = req["type"] as? String ?: "dword"
            if (!writeMemory(address, value, type)) allSuccess = false
        }
        return allSuccess
    }

    /**
     * 分析指定地址周围的内存区域
     */
    fun analyzeMemoryRegion(address: Int, range: Int): Map<String, Any> {
        val pid = attachedPid ?: return emptyMap()

        return try {
            val startAddr = (address.toLong() - range).coerceAtLeast(0)
            val length = range * 2

            val data = readMemoryChunk(pid, startAddr, length) ?: return emptyMap()

            // 转换为十六进制字符串
            val hexString = data.joinToString("") { "%02x".format(it) }

            mapOf(
                "address" to address,
                "range" to range,
                "data" to hexString,
                "size" to data.size
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ==================== 底层 IO 操作 ====================

    /**
     * 分块读取内存（2MB chunk）
     * 使用持久化 Root Shell，避免频繁 su 弹窗
     */
    private fun readMemoryChunk(pid: Int, address: Long, size: Int): ByteArray? {
        return try {
            val hexAddr = address.toString(16)
            val result = RootManager.executeRootCommand(
                "xxd -s 0x$hexAddr -l $size -p /proc/$pid/mem 2>/dev/null"
            ) ?: return null

            val hexStr = result.trim().replace(" ", "").replace("\n", "")
            if (hexStr.length < size * 2) return null

            hexStringToBytes(hexStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取指定地址的字节数组
     */
    private fun readMemoryBytes(pid: Int, address: Long, size: Int): ByteArray? {
        return readMemoryChunk(pid, address, size)
    }

    /**
     * 写入字节数组到指定地址
     * 使用 printf + dd，避免创建临时文件
     */
    private fun writeMemoryBytes(pid: Int, address: Long, bytes: ByteArray): Boolean {
        return try {
            val hexAddr = address.toString(16)
            val hexValue = bytes.joinToString("") { "%02x".format(it) }
            val byteCount = bytes.size

            // 使用 printf 写入
            val escapedHex = hexValue.chunked(2).joinToString("\\\\x") { "\\\\x$it" }
            val cmd = "printf '$escapedHex' | dd of=/proc/$pid/mem bs=1 seek=0x$hexAddr count=$byteCount conv=notrunc 2>/dev/null"
            val result = RootManager.executeRootCommand(cmd)
            result != null
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 搜索辅助函数 ====================

    /**
     * 在 chunk 中搜索目标字节序列
     */
    private fun searchInChunk(chunk: ByteArray, target: ByteArray): List<Int> {
        val results = mutableListOf<Int>()
        if (target.isEmpty() || chunk.size < target.size) return results

        for (i in 0..chunk.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (chunk[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                results.add(i)
            }
        }

        return results
    }

    /**
     * 在 chunk 中搜索 AOB 模式（支持通配符）
     */
    private fun searchAobInChunk(chunk: ByteArray, pattern: ByteArray, mask: String?): List<Int> {
        val results = mutableListOf<Int>()
        if (pattern.isEmpty() || chunk.size < pattern.size) return results

        for (i in 0..chunk.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                // 如果有 mask，跳过通配符位置
                if (mask != null && j < mask.length && mask[j] == '?') continue

                if (chunk[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                results.add(i)
            }
        }

        return results
    }

    /**
     * 解析 AOB 模式字符串
     * 支持格式："48 89 5C 24 ? 48 89 74 24 ?" 或 "48895C24??48897424"
     */
    private fun parseAobPattern(pattern: String): ByteArray {
        val cleanPattern = pattern.replace(" ", "").replace("?", "00")
        val bytes = mutableListOf<Byte>()

        var i = 0
        while (i < cleanPattern.length - 1) {
            val byteStr = cleanPattern.substring(i, i + 2)
            if (byteStr == "00" && pattern.contains("?")) {
                bytes.add(0)  // 通配符位置
            } else {
                bytes.add(byteStr.toInt(16).toByte())
            }
            i += 2
        }

        return bytes.toByteArray()
    }

    // ==================== 数据转换函数 ====================

    /**
     * 获取数据类型大小
     */
    fun getTypeSize(type: String): Int {
        return when (type) {
            "byte" -> 1
            "word" -> 2
            "dword" -> 4
            "qword" -> 8
            "float" -> 4
            "double" -> 8
            else -> 4
        }
    }

    /**
     * 值转换为字节数组（Little Endian）
     */
    private fun valueToBytes(value: Any, type: String): ByteArray? {
        return try {
            when (type) {
                "byte" -> {
                    val v = (value as? Number)?.toInt() ?: return null
                    byteArrayOf(v.toByte())
                }
                "word" -> {
                    val v = (value as? Number)?.toInt() ?: return null
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
                }
                "dword" -> {
                    val v = (value as? Number)?.toInt() ?: return null
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
                }
                "qword" -> {
                    val v = (value as? Number)?.toLong() ?: return null
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
                }
                "float" -> {
                    val v = (value as? Number)?.toFloat() ?: return null
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
                }
                "double" -> {
                    val v = (value as? Number)?.toDouble() ?: return null
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 字节数组转换为值（Little Endian）
     */
    private fun bytesToValue(bytes: ByteArray, type: String): Any? {
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            when (type) {
                "byte" -> bytes[0].toInt() and 0xFF
                "word" -> buffer.short.toInt() and 0xFFFF
                "dword" -> buffer.int
                "qword" -> buffer.long
                "float" -> buffer.float
                "double" -> buffer.double
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 十六进制字符串转换为字节数组
     */
    private fun hexStringToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    /**
     * 比较两个值是否相等
     */
    private fun valuesEqual(a: Any, b: Any, type: String): Boolean {
        return try {
            when (type) {
                "float" -> Math.abs((a as Number).toFloat() - (b as Number).toFloat()) < 0.001
                "double" -> Math.abs((a as Number).toDouble() - (b as Number).toDouble()) < 0.0001
                else -> (a as? Number)?.toLong() == (b as? Number)?.toLong()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 比较两个值的大小
     * 返回：正数表示 a > b，负数表示 a < b，0 表示相等
     */
    private fun compareValues(a: Any, b: Any, type: String): Int {
        return try {
            when (type) {
                "float" -> (a as Number).toFloat().compareTo((b as Number).toFloat())
                "double" -> (a as Number).toDouble().compareTo((b as Number).toDouble())
                else -> (a as Number).toLong().compareTo((b as Number).toLong())
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 创建结果 Map
     */
    private fun createResultMap(address: Long, value: Any, type: String): Map<String, Any> {
        return mapOf(
            "address" to "0x${address.toString(16).uppercase()}",
            "addressInt" to address.toInt(),
            "value" to value,
            "type" to type,
            "isFavorite" to false,
            "isFrozen" to false
        )
    }

    /**
     * 保存快照（用于模糊搜索对比）
     */
    private fun saveSnapshot(results: List<Map<String, Any>>, type: String) {
        val pid = attachedPid ?: return
        val typeSize = getTypeSize(type)
        val snapshot = mutableMapOf<Long, ByteArray>()

        for (result in results) {
            val address = result["addressInt"] as? Int ?: continue
            val bytes = readMemoryBytes(pid, address.toLong(), typeSize)
            if (bytes != null) {
                snapshot[address.toLong()] = bytes
            }
        }

        lastSnapshot = snapshot
    }

    // ==================== 数据类 ====================

    data class MemoryRegionInfo(
        val startAddr: Long,
        val endAddr: Long,
        val permissions: String,
        val name: String,
        val priority: Int
    )

    data class AobSignature(
        val address: Long,
        val pattern: String,
        val contextBytes: ByteArray,
        val contextOffset: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AobSignature
            return address == other.address
        }

        override fun hashCode(): Int {
            return address.hashCode()
        }
    }
}
