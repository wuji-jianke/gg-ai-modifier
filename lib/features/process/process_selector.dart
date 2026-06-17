/// 进程选择器页面
/// 扫描运行中的进程并允许用户选择目标游戏

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'dart:io';
import '../../core/models/process_info.dart';

/// 当前附加的进程 Provider
final attachedProcessProvider = StateProvider<ProcessInfo?>((ref) => null);

/// 进程列表 Provider
final processListProvider = StateProvider<List<ProcessInfo>>((ref) => []);

/// 进程选择器页面
class ProcessSelectorPage extends ConsumerStatefulWidget {
  const ProcessSelectorPage({super.key});

  @override
  ConsumerState<ProcessSelectorPage> createState() =>
      _ProcessSelectorPageState();
}

class _ProcessSelectorPageState extends ConsumerState<ProcessSelectorPage> {
  bool _isLoading = false;
  bool _showSystemProcesses = false;
  String _searchQuery = '';
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadProcessList();
    _checkAttachedProcess();
  }

  Future<void> _checkAttachedProcess() async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final pid = await channel.invokeMethod('getAttachedPid');

      if (pid != null && pid > 0 && mounted) {
        // 有附加的进程，获取进程信息
        final result = await channel.invokeMethod('getProcessList', {
          'includeSystem': true,
        });
        if (result != null) {
          final List<dynamic> list = result as List<dynamic>;
          final processes = list.map((item) {
            final map = Map<String, dynamic>.from(item as Map);
            return ProcessInfo.fromJson(map);
          }).toList();

          // 找到对应的进程
          final attachedProcess = processes.firstWhere(
            (p) => p.pid == pid,
            orElse: () => processes.first,
          );

          if (mounted) {
            ref.read(attachedProcessProvider.notifier).state = attachedProcess;
          }
        }
      }
    } catch (e) {
      // 忽略错误
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadProcessList() async {
    setState(() => _isLoading = true);

    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final result = await channel.invokeMethod('getProcessList', {
        'includeSystem': _showSystemProcesses,
      });

      if (result != null && mounted) {
        final List<dynamic> list = result as List<dynamic>;
        final processes = list.map((item) {
          final map = Map<String, dynamic>.from(item as Map);
          return ProcessInfo.fromJson(map);
        }).toList();

        if (mounted) {
          ref.read(processListProvider.notifier).state = processes;
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('获取进程列表失败: $e')));
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _attachProcess(ProcessInfo process) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final result = await channel.invokeMethod('attachProcess', {
        'pid': process.pid,
      });

      if (result == true && mounted) {
        ref.read(attachedProcessProvider.notifier).state = process;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('✅ 已附加到 ${process.displayName}')),
        );
        Navigator.pop(context, process);
      } else if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('❌ 附加进程失败')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('❌ 附加进程失败: $e')));
      }
    }
  }

  List<ProcessInfo> get _filteredProcesses {
    final processes = ref.read(processListProvider);
    if (_searchQuery.isEmpty) return processes;
    return processes
        .where(
          (p) =>
              p.displayName.toLowerCase().contains(
                _searchQuery.toLowerCase(),
              ) ||
              p.packageName.toLowerCase().contains(_searchQuery.toLowerCase()),
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final attachedProcess = ref.watch(attachedProcessProvider);
    final processes = ref.watch(processListProvider);
    final filteredProcesses = _filteredProcesses;
    final countLabel = _showSystemProcesses ? '个进程' : '个应用进程';

    return Scaffold(
      appBar: AppBar(
        title: const Text('选择游戏进程'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _isLoading ? null : _loadProcessList,
          ),
        ],
      ),
      body: Column(
        children: [
          // 当前附加状态
          if (attachedProcess != null)
            Container(
              padding: const EdgeInsets.all(12),
              color: const Color(0xFFFFF9F0),
              child: Row(
                children: [
                  const Icon(Icons.check_circle, color: Colors.green, size: 20),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '当前附加: ${attachedProcess.displayName}',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        Text(
                          '${attachedProcess.packageName} (PID: ${attachedProcess.pid})',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Color(0xFFA1887F),
                          ),
                        ),
                      ],
                    ),
                  ),
                  TextButton(
                    onPressed: () async {
                      try {
                        const channel = MethodChannel('com.yl.aigg/bridge');
                        await channel.invokeMethod('detachProcess');
                        ref.read(attachedProcessProvider.notifier).state = null;
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('已分离进程')),
                          );
                        }
                      } catch (_) {}
                    },
                    child: const Text(
                      '分离',
                      style: TextStyle(color: Colors.red),
                    ),
                  ),
                ],
              ),
            ),

          // 搜索栏
          Padding(
            padding: const EdgeInsets.all(12),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: '搜索游戏名称或包名...',
                prefixIcon: const Icon(Icons.search, size: 16),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 16),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchQuery = '');
                        },
                      )
                    : null,
              ),
              onChanged: (value) {
                setState(() => _searchQuery = value);
              },
            ),
          ),

          // 进程数量
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                Text(
                  '找到 ${filteredProcesses.length} $countLabel',
                  style: const TextStyle(color: Color(0xFFA1887F), fontSize: 12),
                ),
                const Spacer(),
                if (_isLoading)
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              value: _showSystemProcesses,
              title: const Text(
                '显示系统进程',
                style: TextStyle(fontSize: 13),
              ),
              subtitle: const Text(
                '默认只展示普通应用进程',
                style: TextStyle(fontSize: 11, color: Color(0xFFA1887F)),
              ),
              onChanged: (value) {
                setState(() => _showSystemProcesses = value);
                _loadProcessList();
              },
            ),
          ),
          const SizedBox(height: 8),

          // 进程列表
          Expanded(
            child: _isLoading && processes.isEmpty
                ? const Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text('正在扫描进程...', style: TextStyle(color: Color(0xFFA1887F))),
                      ],
                    ),
                  )
                : filteredProcesses.isEmpty
                ? const Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.apps, size: 64, color: Color(0xFFA1887F)),
                        SizedBox(height: 16),
                        Text('未找到应用进程', style: TextStyle(color: Color(0xFFA1887F))),
                        SizedBox(height: 8),
                        Text(
                          '请确保已打开游戏应用',
                          style: TextStyle(color: Color(0xFFA1887F), fontSize: 12),
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    itemCount: filteredProcesses.length,
                    itemBuilder: (context, index) {
                      final process = filteredProcesses[index];
                      final isAttached = attachedProcess?.pid == process.pid;

                      return ListTile(
                        leading: _ProcessIcon(
                          process: process,
                          isAttached: isAttached,
                        ),
                        title: Text(
                          process.displayName,
                          style: TextStyle(
                            fontWeight: isAttached
                                ? FontWeight.bold
                                : FontWeight.normal,
                            fontSize: 14,
                          ),
                        ),
                        subtitle: Text(
                          '${process.packageName}  |  PID: ${process.pid}',
                          style: const TextStyle(
                            fontSize: 11,
                            color: Color(0xFFA1887F),
                          ),
                        ),
                        trailing: isAttached
                            ? const Chip(
                                label: Text(
                                  '已附加',
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: Color(0xFF3E2723),
                                  ),
                                ),
                                backgroundColor: Colors.green,
                                padding: EdgeInsets.zero,
                              )
                            : const Icon(Icons.arrow_forward_ios, size: 16),
                        onTap: isAttached
                            ? null
                            : () => _attachProcess(process),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _ProcessIcon extends StatelessWidget {
  final ProcessInfo process;
  final bool isAttached;

  const _ProcessIcon({required this.process, required this.isAttached});

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: isAttached
            ? Colors.green.withOpacity(0.12)
            : const Color(0xFF8D6E63).withOpacity(0.12),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(
        isAttached ? Icons.check_circle : Icons.apps,
        color: isAttached ? Colors.green : const Color(0xFF8D6E63),
        size: 20,
      ),
    );

    final path = process.iconPath;
    if (path == null || path.isEmpty) {
      return fallback;
    }

    final file = File(path);
    if (!file.existsSync()) {
      return fallback;
    }

    return Container(
      width: 40,
      height: 40,
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.file(
          file,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => fallback,
        ),
      ),
    );
  }
}
