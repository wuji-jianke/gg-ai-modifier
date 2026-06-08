/// 插件中心占位页面
/// 内存搜索功能已迁移至悬浮窗

import 'package:flutter/material.dart';

class PluginCenterPage extends StatelessWidget {
  const PluginCenterPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F0EB),
      appBar: AppBar(
        title: const Row(
          children: [
            Icon(Icons.extension, color: Color(0xFF8D6E63)),
            SizedBox(width: 8),
            Text('插件中心'),
          ],
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 状态卡片
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFFFDFBF7),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: const Color(0xFF8D6E63).withOpacity(0.3),
                ),
              ),
              child: const Row(
                children: [
                  Icon(Icons.layers, color: Color(0xFF8D6E63), size: 28),
                  SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'AI-GG 增强悬浮窗已就绪',
                          style: TextStyle(
                            color: Color(0xFF3E2723),
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        SizedBox(height: 4),
                        Text(
                          '全部搜索与高级数值逻辑已整合至悬浮窗面板',
                          style: TextStyle(color: Color(0xFFA1887F), fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            const Text(
              '🔌 插件扩展槽位',
              style: TextStyle(
                color: Color(0xFF3E2723),
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            // 插件占位网格
            Expanded(
              child: GridView.count(
                crossAxisCount: 2,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                childAspectRatio: 1.2,
                children: List.generate(4, (index) {
                  return Container(
                    decoration: BoxDecoration(
                      color: const Color(0xFFFDFBF7),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: Color(0xFFA1887F).withOpacity(0.3),
                        width: 1,
                        strokeAlign: BorderSide.strokeAlignInside,
                      ),
                    ),
                    child: const Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.extension_off_outlined,
                          size: 36,
                          color: Color(0xFFA1887F),
                        ),
                        SizedBox(height: 8),
                        Text(
                          '已预留动态插件槽位',
                          style: TextStyle(color: Color(0xFFA1887F), fontSize: 11),
                        ),
                        Text(
                          '等待热更新下发',
                          style: TextStyle(color: Color(0xFFA1887F), fontSize: 10),
                        ),
                      ],
                    ),
                  );
                }),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
