package io.github.endlessworld.ohealthmcs;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * OPPO 健康（com.heytap.health）MCS/MCP 音乐控制黑名单解锁模块。
 *
 * <p>入口只做一件事：在目标包首次加载完成时安装 hook。
 * 具体逻辑见 {@link McsHooks}，卸载/热重载由框架负责。
 */
public final class McsUnlockModule extends XposedModule {

    static final String TAG = "OHealthMcsUnlock";
    static final String TARGET_PKG = "com.heytap.health";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }

        log(Log.INFO, TAG, "==================================================");
        log(Log.INFO, TAG, "attaching to " + TARGET_PKG
                + " | api=" + getApiVersion()
                + " | framework=" + getFrameworkName() + " " + getFrameworkVersion());

        try {
            McsHooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed", t);
        }

        log(Log.INFO, TAG, "==================================================");
    }
}
