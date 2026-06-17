/// LLM 配置管理

/// LLM 接口格式
enum LlmApiFormat {
  openAiChatCompletions,
  openAiResponses,
  anthropicMessages,
}

extension LlmApiFormatX on LlmApiFormat {
  String get value {
    switch (this) {
      case LlmApiFormat.openAiChatCompletions:
        return 'openai_chat_completions';
      case LlmApiFormat.openAiResponses:
        return 'openai_responses';
      case LlmApiFormat.anthropicMessages:
        return 'anthropic_messages';
    }
  }

  String get label {
    switch (this) {
      case LlmApiFormat.openAiChatCompletions:
        return 'OAI Chat';
      case LlmApiFormat.openAiResponses:
        return 'OAI Responses';
      case LlmApiFormat.anthropicMessages:
        return 'Anthropic';
    }
  }

  static LlmApiFormat fromValue(String? value) {
    switch (value) {
      case 'openai_responses':
        return LlmApiFormat.openAiResponses;
      case 'anthropic_messages':
        return LlmApiFormat.anthropicMessages;
      case 'openai_chat_completions':
      default:
        return LlmApiFormat.openAiChatCompletions;
    }
  }
}

/// LLM API 配置
class LlmConfig {
  /// API 基础地址
  final String baseUrl;

  /// API 密钥
  final String apiKey;

  /// 模型名称
  final String model;

  /// 接口格式
  final LlmApiFormat apiFormat;

  /// 温度参数 (0.0 - 2.0)
  final double temperature;

  /// 最大 token 数
  final int maxTokens;

  /// 是否启用流式响应
  final bool streamEnabled;

  /// 请求超时时间 (秒)
  final int timeoutSeconds;

  /// 已获取到的模型列表
  final List<String> availableModels;

  const LlmConfig({
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    this.apiFormat = LlmApiFormat.openAiChatCompletions,
    this.temperature = 0.7,
    this.maxTokens = 4096,
    this.streamEnabled = true,
    this.timeoutSeconds = 60,
    this.availableModels = const [],
  });

  LlmConfig copyWith({
    String? baseUrl,
    String? apiKey,
    String? model,
    LlmApiFormat? apiFormat,
    double? temperature,
    int? maxTokens,
    bool? streamEnabled,
    int? timeoutSeconds,
    List<String>? availableModels,
  }) {
    return LlmConfig(
      baseUrl: baseUrl ?? this.baseUrl,
      apiKey: apiKey ?? this.apiKey,
      model: model ?? this.model,
      apiFormat: apiFormat ?? this.apiFormat,
      temperature: temperature ?? this.temperature,
      maxTokens: maxTokens ?? this.maxTokens,
      streamEnabled: streamEnabled ?? this.streamEnabled,
      timeoutSeconds: timeoutSeconds ?? this.timeoutSeconds,
      availableModels: availableModels ?? this.availableModels,
    );
  }

  /// 是否已配置
  bool get isConfigured => baseUrl.isNotEmpty && apiKey.isNotEmpty;

  /// API 完整地址
  String get chatEndpoint {
    switch (apiFormat) {
      case LlmApiFormat.openAiChatCompletions:
        return '$baseUrl/chat/completions';
      case LlmApiFormat.openAiResponses:
        return '$baseUrl/responses';
      case LlmApiFormat.anthropicMessages:
        return '$baseUrl/messages';
    }
  }

  Map<String, dynamic> toJson() {
    return {
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
      'apiFormat': apiFormat.value,
      'temperature': temperature,
      'maxTokens': maxTokens,
      'streamEnabled': streamEnabled,
      'timeoutSeconds': timeoutSeconds,
      'availableModels': availableModels,
    };
  }

  factory LlmConfig.fromJson(Map<String, dynamic> json) {
    return LlmConfig(
      baseUrl: json['baseUrl'] as String? ?? '',
      apiKey: json['apiKey'] as String? ?? '',
      model: json['model'] as String? ?? '',
      apiFormat: LlmApiFormatX.fromValue(json['apiFormat'] as String?),
      temperature: (json['temperature'] as num?)?.toDouble() ?? 0.7,
      maxTokens: json['maxTokens'] as int? ?? 4096,
      streamEnabled: json['streamEnabled'] as bool? ?? true,
      timeoutSeconds: json['timeoutSeconds'] as int? ?? 60,
      availableModels: ((json['availableModels'] as List?) ?? const [])
          .map((item) => item.toString())
          .toList(),
    );
  }

  /// 预设配置
  static const presets = {
    'deepseek': LlmConfig(
      baseUrl: 'https://api.deepseek.com',
      apiKey: '',
      model: 'deepseek-chat',
      apiFormat: LlmApiFormat.openAiChatCompletions,
    ),
    'deepseek-reasoner': LlmConfig(
      baseUrl: 'https://api.deepseek.com',
      apiKey: '',
      model: 'deepseek-reasoner',
      apiFormat: LlmApiFormat.openAiChatCompletions,
    ),
    'xiaomi-mimo': LlmConfig(
      baseUrl: 'https://api.xiaomimimo.com/v1',
      apiKey: '',
      model: 'mimo-v2.5-pro',
      apiFormat: LlmApiFormat.openAiChatCompletions,
    ),
    'anthropic': LlmConfig(
      baseUrl: 'https://api.anthropic.com/v1',
      apiKey: '',
      model: 'claude-3-5-sonnet-latest',
      apiFormat: LlmApiFormat.anthropicMessages,
    ),
    'openai': LlmConfig(
      baseUrl: 'https://api.openai.com/v1',
      apiKey: '',
      model: 'gpt-4o',
      apiFormat: LlmApiFormat.openAiChatCompletions,
    ),
  };

  /// 获取预设配置列表
  static List<MapEntry<String, LlmConfig>> get presetList {
    return presets.entries.toList();
  }

  @override
  String toString() {
    return 'LlmConfig(model: $model, baseUrl: $baseUrl, apiFormat: ${apiFormat.value})';
  }
}
