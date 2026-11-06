package io.github.endlessworld.ohealthmcs;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import io.github.libxposed.api.XposedModule;

/**
 * 清理 OPPO 健康（com.heytap.health）音乐控制黑名单。
 *
 * <p>反编译结论（6.10.13_16d7513_261030 / versionCode 6101300，17 个 dex）：
 * <pre>
 * 类 Lr51.a; —— R8 合并出来的多用途类，实现 22 个接口
 *   public static volatile k : Lr51.a;   // 缓存实例
 *   public final synthetic  i : I        // 构造参数（分支选择器）
 *   public final            j : Object   // 分支 0xE 下是 android.util.ArraySet —— 黑名单容器
 *
 * 分支 0xE 的 &lt;init&gt;(I)V：
 *   j = new ArraySet();
 *   add("com.duowan.kiwi"); add("tv.danmaku.bili"); add("com.bilibili.app.blue");
 *   add("com.bilibili.app.in"); add("com.coloros.video"); add("com.coloros.yoli");
 *   add("com.heytap.yoli"); add("com.android.chrome"); add("com.opera.browser");
 *   add("com.smile.gifmaker"); add("com.kuaishou.nebula");
 *   随后读取 sp_music_transfer 的 key_server_config 交给 Q(String) 继续追加
 *
 * Q(Ljava/lang/String;)V：
 *   解析 JSON 的 "packageName" 数组，逐个 add 进同一个 ArraySet；
 *   异常时打日志 "[parseConfigStr] error -> "（tag: MCS_Blacklist）
 *
 * 消费点 a71.a#handleMessage：
 *   r.j (ArraySet).contains(pkg) == true 时打 "music controller in blacklist: " 并跳过该控制器
 * </pre>
 *
 * <p>因此本模块不修改任何方法体，只保证那个 ArraySet 始终为空：
 * <ol>
 *   <li>hook 构造函数：原逻辑跑完后清空容器（抹掉硬编码包名）；</li>
 *   <li>hook {@code Q(String)}：原方法跑完后再次清空容器（抹掉服务端下发内容）；</li>
 *   <li>hook 持有类 {@code com.heytap.health.watch.music.control.r} 的类初始化器，
 *       初始化结束后补一次清空（构造若被 R8 内联，ctor hook 不会触发）；</li>
 *   <li>模块挂载时若实例已存在，直接反射清空。</li>
 * </ol>
 */
final class McsHooks {

    private static final String TAG = "OHealthMcs";

    /** 被 R8 合并的类。 */
    private static final String TARGET_CLASS = "r51.a";

    /** 持有实例的类与静态字段。 */
    private static final String HOLDER_CLASS = "com.heytap.health.watch.music.control.r";
    private static final String HOLDER_FIELD = "a";

    /** 分支选择器字段。 */
    private static final String CASE_FIELD = "i";
    /** 只有 0xE 分支的 j 才是黑名单集合。 */
    private static final int CASE_BLACKLIST = 0xE;

    /** 黑名单容器字段。 */
    private static final String SET_FIELD = "j";

    private static final Object LOCK = new Object();

    private McsHooks() {
    }

    static void install(final XposedModule module, final ClassLoader cl) {
        final Class<?> target;
        try {
            target = Class.forName(TARGET_CLASS, false, cl);
        } catch (final Throwable t) {
            module.log(Log.ERROR, TAG, "target class " + TARGET_CLASS + " not found: " + t);
            return;
        }
        module.log(Log.INFO, TAG, "target class resolved: " + target.getName());

        hookConstructor(module, target);
        hookParseConfig(module, target);
        hookHolderInitializer(module, cl, target);
        scrubExisting(module, cl);
    }

    /** {@code <init>(I)V}：原构造完成后清空黑名单集合（去掉硬编码包名）。 */
    private static void hookConstructor(final XposedModule module, final Class<?> target) {
        final Constructor<?> ctor;
        try {
            ctor = target.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
        } catch (final Throwable t) {
            module.log(Log.ERROR, TAG, "constructor <init>(I) not found: " + t);
            return;
        }

        try {
            module.hook(ctor).intercept(chain -> {
                final Object result = chain.proceed();
                try {
                    final Object self = chain.getThisObject();
                    if (self != null && caseOf(self) == CASE_BLACKLIST) {
                        purge(module, self, "ctor");
                    }
                } catch (final Throwable t) {
                    module.log(Log.WARN, TAG, "ctor purge failed: " + t);
                }
                return result;
            });
            module.log(Log.INFO, TAG, "hooked <init>(I) -> purge blacklist");
        } catch (final Throwable t) {
            module.log(Log.ERROR, TAG, "hook <init>(I) failed: " + t);
        }
    }

    /** {@code Q(String)}：原方法执行后清空集合，等价于丢弃它写入的全部包名。 */
    private static void hookParseConfig(final XposedModule module, final Class<?> target) {
        final Method q;
        try {
            q = target.getDeclaredMethod("Q", String.class);
            q.setAccessible(true);
        } catch (final Throwable t) {
            module.log(Log.WARN, TAG, "method Q(String) not found: " + t);
            return;
        }

        try {
            module.hook(q).intercept(chain -> {
                Object result = null;
                try {
                    result = chain.proceed();
                } finally {
                    try {
                        final Object self = chain.getThisObject();
                        if (self != null) {
                            purge(module, self, "Q");
                        }
                    } catch (final Throwable t) {
                        module.log(Log.WARN, TAG, "Q purge failed: " + t);
                    }
                }
                return result;
            });
            module.log(Log.INFO, TAG, "hooked Q(String) -> purge after call");
        } catch (final Throwable t) {
            module.log(Log.ERROR, TAG, "hook Q(String) failed: " + t);
        }
    }

    /**
     * 持有类 {@code com.heytap.health.watch.music.control.r} 的类初始化器：
     * 实例在这里创建，创建后可能立刻读配置调 Q。初始化结束时再清一次作为保险。
     */
    private static void hookHolderInitializer(final XposedModule module,
                                              final ClassLoader cl,
                                              final Class<?> target) {
        final Class<?> holder;
        try {
            holder = Class.forName(HOLDER_CLASS, false, cl);
        } catch (final Throwable t) {
            module.log(Log.WARN, TAG, "holder class " + HOLDER_CLASS + " not found: " + t);
            return;
        }

        try {
            module.hookClassInitializer(holder).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } finally {
                    try {
                        purgeHolder(module, holder, target, "holder-clinit");
                    } catch (final Throwable t) {
                        module.log(Log.WARN, TAG, "holder-clinit purge failed: " + t);
                    }
                }
                return result;
            });
            module.log(Log.INFO, TAG, "hooked " + HOLDER_CLASS + ".<clinit> -> purge singleton");
        } catch (final Throwable t) {
            module.log(Log.WARN, TAG, "hook holder initializer skipped: " + t);
        }
    }

    /** 安装时兜底：实例可能在本模块 attach 之前就已构造完成。 */
    private static void scrubExisting(final XposedModule module, final ClassLoader cl) {
        try {
            final Class<?> holder = Class.forName(HOLDER_CLASS, false, cl);
            purgeHolder(module, holder, null, "existing-singleton");
        } catch (final Throwable t) {
            module.log(Log.WARN, TAG, "scrubExisting skipped: " + t);
        }
    }

    private static void purgeHolder(final XposedModule module,
                                    final Class<?> holder,
                                    final Class<?> target,
                                    final String reason) throws Throwable {
        final Field f = holder.getDeclaredField(HOLDER_FIELD);
        f.setAccessible(true);
        final Object singleton = f.get(null);
        if (singleton == null) {
            return;
        }
        if (target != null && !target.isInstance(singleton)) {
            return;
        }
        purge(module, singleton, reason);
    }

    /** 读 i 字段（分支选择器）；读不到就当命中，避免漏清。 */
    private static int caseOf(final Object self) {
        try {
            final Field f = findField(self.getClass(), CASE_FIELD);
            return f == null ? CASE_BLACKLIST : f.getInt(self);
        } catch (final Throwable t) {
            return CASE_BLACKLIST;
        }
    }

    /** 取 j 字段，若为集合则清空并记录被剔除的条目。 */
    private static void purge(final XposedModule module, final Object self, final String reason) {
        synchronized (LOCK) {
            try {
                final Field f = findField(self.getClass(), SET_FIELD);
                if (f == null) {
                    module.log(Log.WARN, TAG, "field " + SET_FIELD + " not found on "
                            + self.getClass().getName());
                    return;
                }
                final Object value = f.get(self);
                if (!(value instanceof Collection)) {
                    // 非 0xE 分支的 j 不是集合（例如 AtomicReference），直接放过
                    return;
                }
                final Collection<?> set = (Collection<?>) value;
                if (set.isEmpty()) {
                    return;
                }
                final int n = set.size();
                final String head = n <= 8 ? String.valueOf(set) : (n + " entries, e.g. "
                        + firstFew(set, 8));
                set.clear();
                module.log(Log.INFO, TAG, "purged " + n + " blacklist entries ("
                        + reason + "): " + head);
            } catch (final Throwable t) {
                module.log(Log.WARN, TAG, "purge failed: " + t);
            }
        }
    }

    private static String firstFew(final Collection<?> c, final int max) {
        final StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (final Object o : c) {
            if (i >= max) {
                sb.append(", ...");
                break;
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(o);
            i++;
        }
        return sb.append(']').toString();
    }

    private static Field findField(final Class<?> cls, final String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (final NoSuchFieldException ignored) {
                // 继续向上找
            }
        }
        return null;
    }
}
