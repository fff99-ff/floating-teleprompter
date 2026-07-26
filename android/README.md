# 悬浮提词器 Android App

## 项目结构

```
android/
├── build.gradle                    # 项目级构建配置
├── settings.gradle                 # 项目设置
├── gradle.properties               # Gradle 属性
├── gradle/wrapper/
│   └── gradle-wrapper.properties   # Gradle 版本配置
└── app/
    ├── build.gradle                # App 模块构建配置
    ├── proguard-rules.pro          # ProGuard 规则
    └── src/main/
        ├── AndroidManifest.xml     # 清单文件（权限+组件声明）
        ├── java/com/teleprompter/floating/
        │   ├── MainActivity.kt          # 主界面（设置+启动悬浮窗）
        │   └── FloatingTeleprompterService.kt  # 悬浮窗服务
        ├── assets/
        │   └── teleprompter.html    # WebView 加载的提词器页面
        └── res/
            ├── layout/
            │   ├── activity_main.xml     # 主界面布局
            │   └── floating_window.xml   # 悬浮窗布局
            ├── values/                   # 字符串/颜色/主题
            ├── drawable/                 # 图标/形状
            └── mipmap-anydpi-v26/        # 自适应图标
```

## 打包步骤

### 方法一：Android Studio（推荐）

1. 下载安装 [Android Studio](https://developer.android.com/studio)
2. 打开 Android Studio → `File` → `Open` → 选择 `android/` 目录
3. 等待 Gradle 同步完成（首次需要下载依赖）
4. 连接 Android 手机（开启 USB 调试）或创建模拟器
5. 点击 `Run` 按钮直接安装运行
6. 或者 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)` 生成 APK

### 方法二：命令行

```bash
# 确保已安装 Android SDK 和 Gradle 8.2+
cd android

# 生成 Debug APK
./gradlew assembleDebug

# APK 位置：app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 安装 APK 后打开「悬浮提词器」
2. 输入或导入台词文本
3. 调节字体大小、颜色、背景透明度、滚动速度等
4. 点击「启动悬浮提词」
5. 首次使用会提示授权「显示在其他应用上层」
6. 授权后悬浮窗自动出现，同时返回桌面
7. 打开任意应用（如相机、直播软件），悬浮窗会显示在最上层

## 悬浮窗操作

| 操作 | 效果 |
|------|------|
| 拖动顶部栏 | 移动悬浮窗位置 |
| 点击悬浮窗 | 暂停 / 继续滚动 |
| 上下滑动悬浮窗 | 调节滚动速度 |
| 点击 ⊝ 按钮 | 最小化为图标 |
| 点击最小化图标 | 恢复悬浮窗 |
| 点击 ✕ 按钮 | 关闭悬浮窗 |
| 底部速度滑块 | 精确调节速度 |
| 底部 ▶/⏸ 按钮 | 播放/暂停 |

## 功能特性

- **系统级悬浮**：真正的跨应用悬浮窗，可在任何 App 上层显示
- **语音同步**：TTS 朗读驱动字幕滚动，说到哪滚到哪
- **自由拖动**：悬浮窗可拖到屏幕任意位置
- **最小化**：不用时缩为小图标
- **透明背景**：0-100% 透明度可调
- **字体自定义**：大小、颜色自由调节
- **镜像翻转**：支持反射式提词器
- **设置持久化**：所有设置自动保存

## 最低系统要求

- Android 7.0 (API 24) 及以上
- 需要授予「显示在其他应用上层」权限
