/// GG Modifier 应用入口

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'features/home/home_page.dart';

/// 应用主题色（奶白色暖色系）
const Color kPrimaryColor = Color(0xFF8D6E63);       // 柔和棕色
const Color kSecondaryColor = Color(0xFF6D4C41);     // 辅助浅棕色
const Color kBackgroundColor = Color(0xFFFDFBF7);    // 奶白色背景
const Color kSurfaceColor = Color(0xFFFFF9F0);       // 浅奶黄卡片
const Color kErrorColor = Color(0xFFA1887F);         // 柔和错误色
const Color kTextPrimary = Color(0xFF3E2723);        // 深棕色文字
const Color kTextSecondary = Color(0xFF8D6E63);      // 辅助文字
const Color kAccentColor = Color(0xFF4E342E);        // 深色动作
const Color kSplashColor = Color(0xFFFCEFDC);        // 激活浅黄色

/// GG Modifier 主应用
class GgModifierApp extends ConsumerWidget {
  const GgModifierApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp(
      title: 'GG-AI Modifier',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        splashColor: kSplashColor,
        highlightColor: kSplashColor,
        scaffoldBackgroundColor: kBackgroundColor,
        colorScheme: ColorScheme.light(
          primary: kAccentColor,
          secondary: kSecondaryColor,
          surface: kSurfaceColor,
          onSurface: kTextPrimary,
          error: kErrorColor,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: kBackgroundColor,
          elevation: 0,
          centerTitle: true,
          foregroundColor: kTextPrimary,
          surfaceTintColor: Colors.transparent,
        ),
        cardTheme: CardThemeData(
          color: kSurfaceColor,
          elevation: 0,
          shadowColor: const Color(0x0A8D6E63),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: kSurfaceColor,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide.none,
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: kPrimaryColor, width: 2),
          ),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 14,
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: kAccentColor,
            foregroundColor: kSurfaceColor,
            elevation: 0,
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
      ),
      home: const HomePage(),
    );
  }
}

/// 奶油感点击包裹组件
/// 按下时轻微缩放 + 背景色过渡，替代原生水波纹
class MilkClickWrapper extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final BorderRadius borderRadius;
  final Color? backgroundColor;

  const MilkClickWrapper({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.borderRadius = const BorderRadius.all(Radius.circular(12)),
    this.backgroundColor,
  });

  @override
  State<MilkClickWrapper> createState() => _MilkClickWrapperState();
}

class _MilkClickWrapperState extends State<MilkClickWrapper>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _scaleAnim;
  late final Animation<Color?> _colorAnim;

  static const _duration = Duration(milliseconds: 150);
  static const _pressedScale = 0.96;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: _duration);
    _scaleAnim = Tween<double>(begin: 1.0, end: _pressedScale).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final base = widget.backgroundColor ?? kSurfaceColor;
    _colorAnim = ColorTween(begin: base, end: kSplashColor).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onTapDown(TapDownDetails _) => _controller.forward();

  void _onTapUp(TapUpDetails _) {
    _controller.reverse();
    widget.onTap?.call();
  }

  void _onTapCancel() => _controller.reverse();

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: _onTapDown,
      onTapUp: _onTapUp,
      onTapCancel: _onTapCancel,
      onLongPressStart: (_) {
        _controller.forward();
        widget.onLongPress?.call();
      },
      onLongPressEnd: (_) => _controller.reverse(),
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return Transform.scale(
            scale: _scaleAnim.value,
            child: Container(
              decoration: BoxDecoration(
                color: _colorAnim.value,
                borderRadius: widget.borderRadius,
              ),
              child: child,
            ),
          );
        },
        child: widget.child,
      ),
    );
  }
}
