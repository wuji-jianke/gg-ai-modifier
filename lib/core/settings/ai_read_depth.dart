/// AI 读取深度选项
enum AiReadDepth {
  sample20(20, '前 20 条'),
  sample30(30, '前 30 条'),
  sample50(50, '前 50 条'),
  sample100(100, '前 100 条'),
  full(-1, '全部结果');

  final int value;
  final String label;

  const AiReadDepth(this.value, this.label);
}
