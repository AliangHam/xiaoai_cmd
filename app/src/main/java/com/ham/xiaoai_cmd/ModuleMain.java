package com.ham.xiaoai_cmd;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed 模块入口。
 * 目标：com.miui.voiceassist（小米语音助手）
 * 功能：Hook 文本输入、语音识别结果和返回结果，通过通知显示捕获内容。
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "XiaoAiCmd";
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";
    private volatile String loadedProcess;
    private volatile boolean installed;

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
        installer.installAll(classLoader);
        installed = true;
    }
}
