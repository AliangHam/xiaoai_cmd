package com.ham.xiaoai_cmd;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed 模块入口。
 * 目标：com.miui.voiceassist（小米语音助手）
 * 功能：Hook 文本输入、语音识别结果和返回结果，通过通知显示捕获内容。
 * 支持 API 102 Hot Reload。
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "XiaoAiCmd";
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";
    private volatile String loadedProcess;
    private volatile boolean installed;

    /** 已注册的 Hook Handle，用于 Hot Reload 时清理 */
    private final List<XposedInterface.HookHandle> hookHandles =
            Collections.synchronizedList(new ArrayList<>());

    public ModuleMain() {
        // 无参构造，LSPosed 框架要求
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        loadedProcess = param.getProcessName();
        log(Log.INFO, TAG, "event=module_loaded process=" + loadedProcess
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "event=package_ready package=" + param.getPackageName()
                + " process=" + loadedProcess);
        installHooks(param.getClassLoader());
    }

    private synchronized void installHooks(ClassLoader classLoader) {
        if (installed) {
            log(Log.INFO, TAG, "event=install_skipped reason=already_installed");
            return;
        }
        XiaoAiHookInstaller installer = new XiaoAiHookInstaller(this);
        installer.installAll(classLoader, hookHandles);
        installed = true;
    }

    // ------------------------------------------------------------------
    // Hot Reload — API 102
    // ------------------------------------------------------------------

    /**
     * 旧代码回调：框架即将替换为新代码。
     * 返回 true 允许热重载，返回 false 拒绝。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        log(Log.INFO, TAG, "event=hot_reloading process=" + loadedProcess
                + " hooks=" + hookHandles.size());

        // 保存简单状态供新代码读取
        param.setSavedInstanceState(loadedProcess);

        // 取消所有旧 Hook
        for (XposedInterface.HookHandle handle : hookHandles) {
            try {
                handle.unhook();
            } catch (Exception e) {
                log(Log.WARN, TAG, "event=unhook_failed id=" + handle.getId(), e);
            }
        }
        hookHandles.clear();
        installed = false;

        return true; // 允许热重载
    }

    /**
     * 新代码回调：新代码已加载，准备接管。
     * 此时旧 Hook 已失效，需要重新安装。
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        String savedProcess = (String) param.getSavedInstanceState();
        log(Log.INFO, TAG, "event=hot_reloaded process=" + loadedProcess
                + " saved_process=" + savedProcess);

        // unhook 残留的旧 handle（防御性清理）
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Exception ignored) {
            }
        }

        // 重置安装标记，让下次 onPackageReady 重新安装
        installed = false;
        log(Log.INFO, TAG, "event=hot_reload_complete waiting_for_package_ready");
    }
}
