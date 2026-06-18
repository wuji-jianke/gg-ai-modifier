/// 内存搜索页面 - 紧凑设计

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/memory_result.dart';
import '../process/process_selector.dart';

/// 搜索类型
enum SearchType { exact, fuzzy, range, aob }

/// 当前搜索类型 Provider
final searchTypeProvider = StateProvider<SearchType>((ref) => SearchType.exact);

/// 搜索结果 Provider
final searchResultsProvider = StateProvider<List<MemoryResult>>((ref) => []);

/// 数据类型 Provider
final dataTypeProvider = StateProvider<DataType>((ref) => DataType.dword);

/// 控制面板是否折叠
final controlsCollapsedProvider = StateProvider<bool>((ref) => false);

/// 内存搜索页面
class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key});

  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  final TextEditingController _valueController = TextEditingController();
  final TextEditingController _minController = TextEditingController();
  final TextEditingController _maxController = TextEditingController();
  String _selectedFuzzyComparison = 'changed';
  bool _isSearching = false;
  bool _fuzzyPrimed = false;
  String? _statusHint;

  @override
  void dispose() {
    _valueController.dispose();
    _minController.dispose();
    _maxController.dispose();
    super.dispose();
  }

  void _selectSearchType(SearchType type) {
    ref.read(searchTypeProvider.notifier).state = type;
    if (type == SearchType.fuzzy) {
      ref.read(dataTypeProvider.notifier).state = DataType.dword;
      setState(() {
        _fuzzyPrimed = false;
        _statusHint = '模糊搜索当前仅支持 DWord，请先执行一次全扫描。';
      });
    }
  }

  void _selectDataType(DataType type) {
    final currentType = ref.read(dataTypeProvider);
    if (currentType == type) return;
    ref.read(dataTypeProvider.notifier).state = type;
    setState(() {
      _fuzzyPrimed = false;
      if (ref.read(searchTypeProvider) == SearchType.fuzzy) {
        _statusHint = '数据类型已切换，需重新执行模糊全扫描。';
      }
    });
  }

  Future<void> _performSearch() async {
    final attachedProcess = ref.read(attachedProcessProvider);
    if (attachedProcess == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先附加游戏进程')));
      return;
    }

    final searchType = ref.read(searchTypeProvider);
    final dataType = ref.read(dataTypeProvider);

    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      setState(() {
        _isSearching = true;
        _statusHint = null;
      });
      ref.read(searchResultsProvider.notifier).state = [];

      List<dynamic>? rawResults;

      switch (searchType) {
        case SearchType.range:
          final min = int.tryParse(_minController.text);
          final max = int.tryParse(_maxController.text);
          if (min == null || max == null) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('请输入有效的范围值')));
            return;
          }
          rawResults =
              await channel.invokeMethod('searchByRange', {
                    'minValue': min,
                    'maxValue': max,
                    'type': dataType.name,
                  })
                  as List<dynamic>?;
          break;

        case SearchType.fuzzy:
          if (!_fuzzyPrimed) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('请先执行一次模糊全扫描')));
            return;
          }
          rawResults =
              await channel.invokeMethod('searchFuzzy', {
                    'comparison': _selectedFuzzyComparison,
                    'type': dataType.name,
                  })
                  as List<dynamic>?;
          break;

        case SearchType.aob:
          final pattern = _valueController.text.trim();
          if (pattern.isEmpty) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('请输入特征码')));
            return;
          }
          rawResults =
              await channel.invokeMethod('searchAob', {'pattern': pattern})
                  as List<dynamic>?;
          break;

        case SearchType.exact:
          final value = _valueController.text.trim();
          if (value.isEmpty) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('请输入搜索值')));
            return;
          }

          dynamic searchValue;
          switch (dataType) {
            case DataType.float:
            case DataType.double:
              searchValue = double.tryParse(value);
              break;
            default:
              searchValue = int.tryParse(value);
              break;
          }

          if (searchValue == null) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('请输入有效的数值')));
            return;
          }

          rawResults =
              await channel.invokeMethod('searchExact', {
                    'value': searchValue,
                    'type': dataType.name,
                  })
                  as List<dynamic>?;
          break;
      }

      if (rawResults != null) {
        final results = rawResults.map((item) {
          return MemoryResult.fromJson(Map<String, dynamic>.from(item as Map));
        }).toList();

        ref.read(searchResultsProvider.notifier).state = results;
        // 有结果后自动折叠控制面板
        if (results.isNotEmpty) {
          ref.read(controlsCollapsedProvider.notifier).state = true;
        }
        setState(() {
          if (searchType == SearchType.fuzzy) {
            _fuzzyPrimed = true;
          }
          _statusHint = '找到 ${results.length} 个结果';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('找到 ${results.length} 个结果'),
            duration: const Duration(seconds: 1),
          ),
        );
      }
    } catch (e) {
      setState(() {
        _statusHint = '搜索失败: $e';
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('搜索失败: $e')));
    } finally {
      if (mounted) {
        setState(() {
          _isSearching = false;
        });
      }
    }
  }

  Future<void> _primeFuzzySearch() async {
    final attachedProcess = ref.read(attachedProcessProvider);
    if (attachedProcess == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先附加游戏进程')));
      return;
    }

    final dataType = ref.read(dataTypeProvider);
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      setState(() {
        _isSearching = true;
        _statusHint = '正在执行模糊全扫描...';
      });
      final rawResults =
          await channel.invokeMethod('primeFuzzySearch', {
                'type': dataType.name,
              })
              as List<dynamic>?;

      final results = (rawResults ?? []).map((item) {
        return MemoryResult.fromJson(Map<String, dynamic>.from(item as Map));
      }).toList();

      ref.read(searchResultsProvider.notifier).state = results;
      ref.read(controlsCollapsedProvider.notifier).state = results.isNotEmpty;
      setState(() {
        _fuzzyPrimed = true;
        _statusHint = '模糊全扫描完成，当前候选 ${results.length} 条';
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('模糊全扫描完成，候选 ${results.length} 条')));
    } catch (e) {
      setState(() {
        _statusHint = '模糊全扫描失败: $e';
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('模糊全扫描失败: $e')));
    } finally {
      if (mounted) {
        setState(() {
          _isSearching = false;
        });
      }
    }
  }

  Future<void> _writeMemory(MemoryResult result, dynamic newValue) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final success = await channel.invokeMethod('writeMemory', {
        'address': result.addressInt,
        'value': newValue,
        'type': result.type.name,
      });
      if (success == true) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已修改 ${result.address} = $newValue')),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('写入失败: $e')));
    }
  }

  Future<void> _freezeMemory(MemoryResult result, dynamic value) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final success = await channel.invokeMethod('freezeMemory', {
        'address': result.addressInt,
        'value': value,
        'type': result.type.name,
      });
      if (success == true) {
        final results = ref.read(searchResultsProvider);
        ref.read(searchResultsProvider.notifier).state = results.map((r) {
          if (r.addressInt == result.addressInt) {
            return r.copyWith(isFrozen: true, frozenValue: value);
          }
          return r;
        }).toList();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已冻结 ${result.address} = $value')),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('冻结失败: $e')));
    }
  }

  Future<void> _unfreezeMemory(MemoryResult result) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      await channel.invokeMethod('unfreezeMemory', {
        'address': result.addressInt,
      });
      final results = ref.read(searchResultsProvider);
      ref.read(searchResultsProvider.notifier).state = results.map((r) {
        if (r.addressInt == result.addressInt) {
          return r.copyWith(isFrozen: false);
        }
        return r;
      }).toList();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('已解冻 ${result.address}')));
    } catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('解冻失败: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final searchType = ref.watch(searchTypeProvider);
    final dataType = ref.watch(dataTypeProvider);
    final results = ref.watch(searchResultsProvider);
    final attachedProcess = ref.watch(attachedProcessProvider);
    final collapsed = ref.watch(controlsCollapsedProvider);
    final availableTypes = searchType == SearchType.fuzzy
        ? const [DataType.dword]
        : DataType.values.where((t) => t != DataType.string).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('内存搜索'),
        actions: [
          // 折叠/展开按钮
          if (results.isNotEmpty)
            IconButton(
              icon: Icon(collapsed ? Icons.expand_more : Icons.expand_less),
              onPressed: () {
                ref.read(controlsCollapsedProvider.notifier).state = !collapsed;
              },
            ),
        ],
      ),
      body: Column(
        children: [
          // 进程状态 + 搜索类型 (紧凑单行)
          _buildTopBar(
            attachedProcess,
            searchType,
            dataType,
            availableTypes,
            collapsed,
          ),
          // 搜索输入区域 (可折叠)
          if (!collapsed) _buildSearchInput(searchType),
          // 结果统计 + 折叠时的搜索按钮
          if (results.isNotEmpty) _buildResultBar(results, collapsed),
          // 搜索结果列表
          Expanded(
            child: results.isEmpty
                ? _buildEmptyState(attachedProcess)
                : _buildResultList(results),
          ),
        ],
      ),
    );
  }

  /// 顶部紧凑栏：进程状态 + 搜索类型切换 + 数据类型
  Widget _buildTopBar(
    dynamic attachedProcess,
    SearchType searchType,
    DataType dataType,
    List<DataType> availableTypes,
    bool collapsed,
  ) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      color: const Color(0xFFFDFBF7),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 第一行：进程状态
          GestureDetector(
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const ProcessSelectorPage()),
              );
            },
            child: Row(
              children: [
                Icon(
                  attachedProcess != null ? Icons.check_circle : Icons.circle,
                  size: 10,
                  color: attachedProcess != null ? Colors.green : Colors.red,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    attachedProcess != null
                        ? '${attachedProcess.packageName} (PID:${attachedProcess.pid})'
                        : '未附加进程 - 点击选择',
                    style: TextStyle(
                      color: attachedProcess != null
                          ? Colors.green
                          : Color(0xFFA1887F),
                      fontSize: 12,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (attachedProcess != null)
                  const Icon(
                    Icons.arrow_forward_ios,
                    size: 12,
                    color: Color(0xFFA1887F),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          // 第二行：搜索类型 + 数据类型 + 搜索/重置按钮
          Row(
            children: [
              // 搜索类型选择
              ...SearchType.values.map((type) {
                final isSelected = type == searchType;
                final labels = {
                  SearchType.exact: '精确',
                  SearchType.fuzzy: '模糊',
                  SearchType.range: '范围',
                  SearchType.aob: '特征码',
                };
                return Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: ChoiceChip(
                    label: Text(
                      labels[type]!,
                      style: const TextStyle(fontSize: 11),
                    ),
                    selected: isSelected,
                    onSelected: (_) => _selectSearchType(type),
                    selectedColor: const Color(0xFF8D6E63),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    labelPadding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                );
              }),
              const Spacer(),
              if (_isSearching)
                const Padding(
                  padding: EdgeInsets.only(right: 8),
                  child: SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              // 数据类型下拉
              Container(
                height: 32,
                padding: const EdgeInsets.symmetric(horizontal: 8),
                decoration: BoxDecoration(
                  color: const Color(0xFFFFF9F0),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<DataType>(
                    value: dataType,
                    isDense: true,
                    dropdownColor: const Color(0xFFFFF9F0),
                    style: const TextStyle(
                      color: Color(0xFF8D6E63),
                      fontSize: 12,
                    ),
                    items: availableTypes
                        .map(
                          (type) => DropdownMenuItem(
                            value: type,
                            child: Text(type.displayName),
                          ),
                        )
                        .toList(),
                    onChanged: (type) {
                      if (type != null) {
                        _selectDataType(type);
                      }
                    },
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// 搜索输入区域
  Widget _buildSearchInput(SearchType searchType) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
      color: const Color(0xFFFFF9F0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildInputByType(searchType),
          if (_statusHint != null) ...[
            const SizedBox(height: 8),
            Text(
              _statusHint!,
              style: const TextStyle(fontSize: 11, color: Color(0xFFA1887F)),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildInputByType(SearchType searchType) {
    switch (searchType) {
      case SearchType.range:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '范围搜索适合已知上下界的数值。',
              style: TextStyle(fontSize: 11, color: Color(0xFFA1887F)),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _minController,
                    decoration: const InputDecoration(
                      hintText: '最小值',
                      prefixIcon: Icon(Icons.arrow_downward, size: 16),
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                      signed: true,
                    ),
                    style: const TextStyle(fontSize: 14),
                  ),
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 8),
                  child: Text('~', style: TextStyle(color: Color(0xFFA1887F))),
                ),
                Expanded(
                  child: TextField(
                    controller: _maxController,
                    decoration: const InputDecoration(
                      hintText: '最大值',
                      prefixIcon: Icon(Icons.arrow_upward, size: 16),
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                      signed: true,
                    ),
                    style: const TextStyle(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 8),
                _buildSearchResetButtons(),
              ],
            ),
          ],
        );

      case SearchType.fuzzy:
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _isSearching ? null : _primeFuzzySearch,
                    icon: const Icon(Icons.radar, size: 18),
                    label: Text(_fuzzyPrimed ? '重新全扫描' : '模糊全扫描'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                _fuzzyChip('changed', '已改变'),
                _fuzzyChip('unchanged', '未改变'),
                _fuzzyChip('increased', '增大'),
                _fuzzyChip('decreased', '减小'),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              '先做一次全扫描建立快照，再用变化条件连续过滤。当前模糊搜索锁定为 DWord。',
              style: TextStyle(fontSize: 11, color: Color(0xFFA1887F)),
            ),
            const SizedBox(height: 8),
            _buildSearchResetButtons(),
          ],
        );

      case SearchType.aob:
        return Row(
          children: [
            Expanded(
              child: TextField(
                controller: _valueController,
                decoration: const InputDecoration(
                  hintText: '特征码 (如: 48 89 5C 24 ? 48)',
                  prefixIcon: Icon(Icons.fingerprint, size: 16),
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 10,
                  ),
                ),
                keyboardType: TextInputType.text,
                style: const TextStyle(fontSize: 14),
              ),
            ),
            const SizedBox(width: 8),
            _buildSearchResetButtons(),
          ],
        );

      case SearchType.exact:
        final dataType = ref.watch(dataTypeProvider);
        final decimalInput =
            dataType == DataType.float || dataType == DataType.double;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '精确搜索用于已知当前值，可多次重复搜索逐步缩小结果。',
              style: TextStyle(fontSize: 11, color: Color(0xFFA1887F)),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _valueController,
                    decoration: InputDecoration(
                      hintText: decimalInput ? '输入整数或小数' : '输入整数数值',
                      prefixIcon: const Icon(Icons.numbers, size: 16),
                      isDense: true,
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                    ),
                    keyboardType: TextInputType.numberWithOptions(
                      signed: true,
                      decimal: decimalInput,
                    ),
                    style: const TextStyle(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 8),
                _buildSearchResetButtons(),
              ],
            ),
          ],
        );
    }
  }

  /// 搜索 + 重置按钮
  Widget _buildSearchResetButtons() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: 40,
          child: ElevatedButton(
            onPressed: _isSearching ? null : _performSearch,
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF8D6E63),
              padding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            child: _isSearching
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.search, size: 20),
          ),
        ),
        const SizedBox(width: 4),
        SizedBox(
          height: 40,
          child: OutlinedButton(
            onPressed: () {
              ref.read(searchResultsProvider.notifier).state = [];
              _valueController.clear();
              _minController.clear();
              _maxController.clear();
              setState(() {
                _fuzzyPrimed = false;
                _statusHint = null;
              });
              ref.read(controlsCollapsedProvider.notifier).state = false;
            },
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 12),
            ),
            child: const Icon(
              Icons.refresh,
              size: 18,
              color: Color(0xFFA1887F),
            ),
          ),
        ),
      ],
    );
  }

  /// 模糊搜索 Chip
  Widget _fuzzyChip(String value, String label) {
    final isSelected = _selectedFuzzyComparison == value;
    return ChoiceChip(
      label: Text(label, style: const TextStyle(fontSize: 11)),
      selected: isSelected,
      onSelected: (_) {
        setState(() {
          _selectedFuzzyComparison = value;
        });
      },
      selectedColor: const Color(0xFF8D6E63),
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      labelPadding: const EdgeInsets.symmetric(horizontal: 4),
    );
  }

  /// 结果统计栏 (折叠时也显示快速搜索按钮)
  Widget _buildResultBar(List<MemoryResult> results, bool collapsed) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      color: const Color(0xFFFDFBF7),
      child: Row(
        children: [
          const Icon(Icons.list_alt, size: 16, color: Color(0xFF8D6E63)),
          const SizedBox(width: 6),
          Text(
            '${results.length} 个结果',
            style: const TextStyle(
              color: Color(0xFF3E2723),
              fontSize: 13,
              fontWeight: FontWeight.bold,
            ),
          ),
          if (results.length > 120)
            const Text(
              ' (显示前120)',
              style: TextStyle(color: Color(0xFFA1887F), fontSize: 11),
            ),
          const Spacer(),
          // 折叠时显示快速搜索按钮
          if (collapsed) ...[
            SizedBox(
              height: 28,
              child: ElevatedButton.icon(
                onPressed: _isSearching ? null : _performSearch,
                icon: const Icon(Icons.search, size: 16),
                label: const Text('搜索', style: TextStyle(fontSize: 12)),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF8D6E63),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                ),
              ),
            ),
            const SizedBox(width: 6),
            SizedBox(
              height: 28,
              child: OutlinedButton(
                onPressed: () {
                  ref.read(controlsCollapsedProvider.notifier).state = false;
                },
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                ),
                child: const Icon(
                  Icons.tune,
                  size: 16,
                  color: Color(0xFFA1887F),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  /// 空状态
  Widget _buildEmptyState(dynamic attachedProcess) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            attachedProcess == null ? Icons.link_off : Icons.search_off,
            size: 48,
            color: Color(0xFFA1887F),
          ),
          const SizedBox(height: 12),
          Text(
            attachedProcess == null ? '请先附加游戏进程' : '输入数值开始搜索',
            style: const TextStyle(color: Color(0xFFA1887F), fontSize: 14),
          ),
        ],
      ),
    );
  }

  /// 结果列表
  Widget _buildResultList(List<MemoryResult> results) {
    final displayResults = results.length > 120
        ? results.sublist(0, 120)
        : results;

    return ListView.builder(
      physics: const ClampingScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      itemCount: displayResults.length,
      itemBuilder: (context, index) {
        return _buildCompactResultItem(displayResults[index]);
      },
    );
  }

  /// 紧凑的结果项
  Widget _buildCompactResultItem(MemoryResult result) {
    final accent = result.isFrozen
        ? const Color(0xFF6D4C41)
        : const Color(0xFF8D6E63);
    final machineCode = result.machineCode?.trim() ?? '';
    final regionName = result.regionName?.trim() ?? '';
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      decoration: BoxDecoration(
        color: const Color(0xFFFDFBF7),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFE7D9CD)),
      ),
      child: InkWell(
        onTap: () => _showEditDialog(result),
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    result.isFrozen ? Icons.lock : Icons.memory,
                    color: accent,
                    size: 18,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      result.address,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 12,
                        color: Color(0xFF8D6E63),
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  PopupMenuButton<String>(
                    icon: const Icon(Icons.more_horiz, size: 18),
                    onSelected: (action) {
                      switch (action) {
                        case 'edit':
                          _showEditDialog(result);
                          break;
                        case 'freeze':
                          if (result.isFrozen) {
                            _unfreezeMemory(result);
                          } else {
                            _showFreezeDialog(result);
                          }
                          break;
                      }
                    },
                    itemBuilder: (context) => [
                      const PopupMenuItem(value: 'edit', child: Text('修改值')),
                      PopupMenuItem(
                        value: 'freeze',
                        child: Text(result.isFrozen ? '解冻' : '冻结'),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  _resultBadge(result.type.displayName),
                  if (regionName.isNotEmpty) _resultBadge(regionName),
                  if (result.isFrozen) _resultBadge('已冻结'),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: _resultMetric(
                      '当前值',
                      '${result.value}',
                      emphasize: true,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: _resultMetric(
                      result.isFrozen ? '冻结值' : '状态',
                      result.isFrozen
                          ? '${result.frozenValue ?? result.value}'
                          : '可修改',
                    ),
                  ),
                ],
              ),
              if (machineCode.isNotEmpty) ...[
                const SizedBox(height: 8),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFF9F0),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        '机器码预览',
                        style: TextStyle(
                          fontSize: 10,
                          color: Color(0xFFA1887F),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _truncateMachineCode(machineCode),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: Color(0xFF5D4037),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _showEditDialog(result),
                      icon: const Icon(Icons.edit_outlined, size: 16),
                      label: const Text('修改'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: () {
                        if (result.isFrozen) {
                          _unfreezeMemory(result);
                        } else {
                          _showFreezeDialog(result);
                        }
                      },
                      style: ElevatedButton.styleFrom(
                        backgroundColor: result.isFrozen
                            ? const Color(0xFF6D4C41)
                            : const Color(0xFF8D6E63),
                      ),
                      icon: Icon(
                        result.isFrozen ? Icons.lock_open : Icons.lock,
                        size: 16,
                      ),
                      label: Text(result.isFrozen ? '解冻' : '冻结'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _resultMetric(String label, String value, {bool emphasize = false}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF9F0),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(fontSize: 10, color: Color(0xFFA1887F)),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: emphasize ? 15 : 13,
              fontWeight: emphasize ? FontWeight.w700 : FontWeight.w500,
              color: const Color(0xFF3E2723),
            ),
          ),
        ],
      ),
    );
  }

  Widget _resultBadge(String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF4EA),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: const TextStyle(fontSize: 10, color: Color(0xFFA1887F)),
      ),
    );
  }

  String _truncateMachineCode(String machineCode) {
    final bytes = machineCode.split(RegExp(r'\s+'));
    if (bytes.length <= 12) return machineCode;
    return '${bytes.take(12).join(' ')} ...';
  }

  void _showEditDialog(MemoryResult result) {
    final controller = TextEditingController(text: result.value.toString());

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFFFFF9F0),
        title: const Text('修改内存值'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '地址: ${result.address}',
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Color(0xFF8D6E63),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              decoration: const InputDecoration(
                labelText: '新值',
                hintText: '输入要修改的值',
              ),
              keyboardType: TextInputType.number,
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () {
              final newValue = controller.text.trim();
              if (newValue.isNotEmpty) {
                dynamic value;
                switch (result.type) {
                  case DataType.float:
                  case DataType.double:
                    value = double.tryParse(newValue);
                    break;
                  default:
                    value = int.tryParse(newValue);
                    break;
                }
                if (value != null) {
                  _writeMemory(result, value);
                }
              }
              Navigator.pop(context);
            },
            child: const Text('修改'),
          ),
        ],
      ),
    );
  }

  void _showFreezeDialog(MemoryResult result) {
    final controller = TextEditingController(text: result.value.toString());

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFFFFF9F0),
        title: const Text('冻结内存值'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '地址: ${result.address}',
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Color(0xFF8D6E63),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              decoration: const InputDecoration(
                labelText: '冻结值',
                hintText: '输入要冻结的值',
              ),
              keyboardType: TextInputType.number,
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () {
              final freezeValue = controller.text.trim();
              if (freezeValue.isNotEmpty) {
                dynamic value;
                switch (result.type) {
                  case DataType.float:
                  case DataType.double:
                    value = double.tryParse(freezeValue);
                    break;
                  default:
                    value = int.tryParse(freezeValue);
                    break;
                }
                if (value != null) {
                  _freezeMemory(result, value);
                }
              }
              Navigator.pop(context);
            },
            child: const Text('冻结'),
          ),
        ],
      ),
    );
  }
}
