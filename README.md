# OPPO 健康 MCS 黑名单解锁

LSPosed（libxposed API 102）模块，用于清空 OPPO 健康 / 欢太健康（`com.heytap.health`）里
"MCS/MCP 音乐控制黑名单"，让被系统拉黑的音乐 App 重新出现在手表媒体控制列表里。

## 逆向结论（6.10.13_16d7513_261030，versionCode 6101300，17 dex / 108455 类）

被 R8 合并的类 `r51.a` 只有三个实例字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `k` | `public static volatile Lr51/a;` | 缓存实例 |
| `i` | `public final synthetic I` | 构造参数，分支选择器 |
| `j` | `public final Ljava/lang/Object;` | `i == 0xE` 时是 `android.util.ArraySet`，即黑名单容器 |

两处写入黑名单的路径：

1. `<init>(I)V` 的 `0xE` 分支：`j = new ArraySet()`，随后硬编码 `add(...)` 一串包名
   （`com.duowan.kiwi`、`tv.danmaku.bili`、`com.bilibili.app.blue`、`com.bilibili.app.in`、
   `com.coloros.video`、`com.coloros.yoli`、`com.heytap.yoli`、`com.android.chrome`、
   `com.opera.browser`、`com.smile.gifmaker`、`com.kuaishou.nebula` …），
   再读 `sp_music_transfer` 的 `key_server_config` 交给 `Q(String)`。
2. `Q(Ljava/lang/String;)V`：解析 JSON，取 `"packageName"` 数组逐个 `add` 进同一个 `ArraySet`；
   异常时打 `"[parseConfigStr] error -> "`（tag `MCS_Blacklist`）。

消费点：`a71.a#handleMessage` 拿 `com.heytap.health.watch.music.control.r.a` 的 `j`（强转 `ArraySet`），
`contains(pkg)` 为真就打 `"music controller in blacklist: "` 并跳过该控制器。

## 模块做法

不改方法体，只保证那个集合始终为空：

- hook `<init>(I)V`：原逻辑跑完后清空 `j`（抹掉硬编码包名）。
- hook `Q(String)`：原方法跑完后再次清空 `j`（抹掉服务端下发内容）。
- hook 持有类 `com.heytap.health.watch.music.control.r` 的类初始化器，结束后补清一次
  （防止构造被 R8 内联后 ctor hook 不触发）。
- 模块 attach 时若实例已存在，反射补清一次。

全部 hook 都做了 try/catch，任何一步失败只打日志，不影响宿主 App 启动。

## 编译

GitHub Actions 工作流见 [`.github/workflows/build.yml`](.github/workflows/build.yml)，
推送到 `main`/`master` 或在 Actions 页面手动 `Run workflow` 即可，产物为
`app/build/outputs/apk/release/*.apk`，以 `ohealth-mcs-unlock-apk` 名称上传。

本地编译需要 JDK 17 + Android SDK 35（`local.properties` 或 `ANDROID_HOME`）：

```bash
./gradlew :app:assembleRelease
```

签名：仓库 Secrets 里配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
时工作流会解出 keystore 并签名；未配置则输出未签名 APK。

## 安装

1. 安装 APK，在 LSPosed 管理器里启用模块；
2. 作用域勾选「OPPO 健康 / 健康」；
3. 强制停止并重开 OPPO 健康，然后在手表端重新打开媒体控制。

## 免责

仅供个人设备上的互操作性研究使用。修改系统级黑名单可能带来耗电、通知栏媒体卡片异常等副作用，
请自行评估风险。
