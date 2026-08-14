package com.ham.xiaoai_cmd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 小米语音助手 Hook 安装器 v4.0
 *
 * 自动适配多版本（v7.13 / v8.0+）混淆变化：
 *
 * Hook 1 — 文本输入发送（三条链路）：
 *   A. 混淆类.onSendClick(String, String)V — 候选 e81/e0(v8) / gf0.d0(v7)
 *   B. a2$d.onSendClick — 子类覆盖版本，直接捕获最终发送
 *   C. V3 ViewModel.onAction — 终极兜底，候选 inputmodulev3/e(v8) / InputModuleViewModelV3(v7)
 *
 * Hook 2 — ASR 语音识别结果：
 *   UiManager.onAsrResult — 稳定单例类，参数类候选 et0/b(v8) / u20.b(v7)
 *
 * 版本适配策略：运行时按类名探测，加载成功即使用，无需硬编码版本号。
 */
final class XiaoAiHookInstaller {
    private static final String TAG = "XiaoAiCmd";

    private static final String CHANNEL_ID = "automation_cmd";
    private static final String CHANNEL_NAME = "自动化指令";

    private static final int NOTIFY_TEXT_CMD = 1001;
    private static final int NOTIFY_ASR = 1002;

    // ------------------------------------------------------------------
    // 文本发送 Hook 候选类（按优先级排列，新版本在前）
    // ------------------------------------------------------------------
    private static final String[] TEXT_SEND_CANDIDATES = {
            "e81.e0",                                              // v8.0 混淆类
            "gf0.d0",                                              // v7.13 混淆类
            "com.xiaomi.voiceassistant.ConversationFragment$d",    // 稳定内部类
    };

    // V3 ViewModel 候选类（v8.0 混淆了，v7.13 未混淆）
    private static final String[] V3_VIEWMODEL_CANDIDATES = {
            "com.xiaomi.voiceassistant.mainui.inputmodulev3.e",    // v8.0 混淆类
            "com.xiaomi.voiceassistant.mainui.inputmodulev3.InputModuleViewModelV3", // v7.13
    };

    // ------------------------------------------------------------------
    // 静态缓存
    // ------------------------------------------------------------------
    private static final ConcurrentHashMap<String, Class<?>> classCache =
            new ConcurrentHashMap<>();
    private static volatile Method cachedCurrentAppMethod;

    // ------------------------------------------------------------------
    // 去重状态
    // ------------------------------------------------------------------
    private volatile String lastText1001 = "";
    private volatile long lastTime1001 = 0;
    private volatile String lastText1002 = "";
    private volatile long lastTime1002 = 0;

    private final ConcurrentHashMap<String, Boolean> hookedKeys = new ConcurrentHashMap<>();

    private final XposedModule module;
    private volatile boolean channelCreated;
    private volatile Context cachedContext;

    XiaoAiHookInstaller(XposedModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------
    // 类查找缓存
    // ------------------------------------------------------------------

    private static Class<?> findClass(ClassLoader cl, String name) {
        Class<?> cached = classCache.get(name);
        if (cached != null) return cached;
        try {
            Class<?> clazz = cl.loadClass(name);
            classCache.put(name, clazz);
            return clazz;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * 从候选列表中找到第一个存在的类。
     */
    private static Class<?> findClassFromCandidates(ClassLoader cl, String[] candidates) {
        for (String name : candidates) {
            Class<?> clazz = findClass(cl, name);
            if (clazz != null) return clazz;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Context 获取
    // ------------------------------------------------------------------

    private Context getContext(Object thisObj) {
        if (cachedContext != null) return cachedContext;
        Context ctx = extractContext(thisObj);
        if (ctx != null) {
            cachedContext = ctx;
            return ctx;
        }
        ctx = getApplicationFromActivityThread();
        if (ctx != null) {
            cachedContext = ctx;
            return ctx;
        }
        return null;
    }

    private static Context getApplicationFromActivityThread() {
        try {
            Method method = cachedCurrentAppMethod;
            if (method == null) {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                method = atClass.getMethod("currentApplication");
                cachedCurrentAppMethod = method;
            }
            Object app = method.invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void ensureChannel(Context context) {
        if (channelCreated) return;
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
                channelCreated = true;
            }
        } catch (Exception e) {
            module.log(Log.WARN, TAG, "event=channel_create_failed", e);
        }
    }

    private void sendNotification(Context context, String title, String content, int notifyId) {
        try {
            ensureChannel(context);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.notify(notifyId, new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setTimeoutAfter(3000)
                    .build());
        } catch (Exception e) {
            module.log(Log.WARN, TAG, "event=notify_failed id=" + notifyId, e);
        }
    }

    private static String trimText(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "...";
        return t;
    }

    private void sendNotificationDedup(int notifyId, String title, String content, Object thisObj) {
        String text = trimText(content, 80);
        if (text.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (notifyId == NOTIFY_TEXT_CMD) {
            if (text.equals(lastText1001) && now - lastTime1001 < 5000) return;
            lastText1001 = text;
            lastTime1001 = now;
        } else if (notifyId == NOTIFY_ASR) {
            if (text.equals(lastText1002) && now - lastTime1002 < 5000) return;
            lastText1002 = text;
            lastTime1002 = now;
        }

        Context ctx = getContext(thisObj);
        if (ctx != null) {
            sendNotification(ctx, title, text, notifyId);
        }
    }

    // ------------------------------------------------------------------
    // 安装所有 Hook
    // ------------------------------------------------------------------

    void installAll(ClassLoader classLoader) {
        installTextSendHooks(classLoader);
        installAsrResultHook(classLoader);
    }

    // ------------------------------------------------------------------
    // Hook 1: 文本输入发送
    // ------------------------------------------------------------------

    private void installTextSendHooks(ClassLoader classLoader) {
        // 策略 A：混淆实现类的 onSendClick
        Class<?> implClass = findClassFromCandidates(classLoader, TEXT_SEND_CANDIDATES);
        if (implClass != null) {
            module.log(Log.INFO, TAG,
                    "event=text_send_class_found class=" + implClass.getName());
            hookTextMethod(implClass, "onSendClick");
        } else {
            module.log(Log.WARN, TAG,
                    "event=all_candidates_failed hook=text_send");
        }

        // 策略 B：a2$d 子类覆盖的 onSendClick（同时存在于 v7.13 和 v8.0）
        Class<?> a2d = findClass(classLoader,
                "com.xiaomi.voiceassistant.a2$d");
        if (a2d != null) {
            hookTextMethod(a2d, "onSendClick");
        }

        // 策略 C：V3 ViewModel.onAction — 终极兜底
        installV3FallbackHook(classLoader);
    }

    private void hookTextMethod(Class<?> clazz, String methodName) {
        String key = clazz.getName() + "#" + methodName;
        if (hookedKeys.containsKey(key)) return;

        Method target = findMethodByName(clazz, methodName);
        if (target == null) {
            module.log(Log.DEBUG, TAG,
                    "event=method_not_found class=" + clazz.getName() + " method=" + methodName);
            return;
        }
        target.setAccessible(true);

        try {
            module.hook(target)
                    .setId("xiaoai_text_" + methodName + "_" + clazz.getSimpleName())
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String text && !text.isEmpty()) {
                                module.log(Log.INFO, TAG,
                                        "event=text_send class=" + clazz.getSimpleName()
                                                + " text=" + text);
                                sendNotificationDedup(NOTIFY_TEXT_CMD, "文本指令",
                                        text, chain.getThisObject());
                            }
                        } catch (Exception e) {
                            module.log(Log.WARN, TAG,
                                    "event=text_send_hook_error method=" + methodName, e);
                        }
                        return chain.proceed();
                    });

            hookedKeys.put(key, true);
            module.log(Log.INFO, TAG,
                    "event=hook_registered class=" + clazz.getName() + " method=" + methodName);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "event=install_failed class=" + clazz.getName() + " method=" + methodName, t);
        }
    }

    /**
     * 策略 C：V3 ViewModel.onAction — 终极兜底。
     * 兼容 v7.13（单参 b.n）和 v8.0（双参 f20/a, f20/i）。
     */
    private void installV3FallbackHook(ClassLoader classLoader) {
        Class<?> v3Class = findClassFromCandidates(classLoader, V3_VIEWMODEL_CANDIDATES);
        if (v3Class == null) {
            module.log(Log.DEBUG, TAG, "event=v3_class_not_found");
            return;
        }

        String key = v3Class.getName() + "#onAction";
        if (hookedKeys.containsKey(key)) return;

        Method target = findMethodByName(v3Class, "onAction");
        if (target == null) {
            module.log(Log.DEBUG, TAG,
                    "event=method_not_found class=" + v3Class.getName() + " method=onAction");
            return;
        }
        target.setAccessible(true);

        try {
            module.hook(target)
                    .setId("xiaoai_v3_onaction")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            // v7.13: onAction(Object action) — arg(0) 是 b.n
                            // v8.0:  onAction(f20/a, f20/i) — arg(0) 是 action 类型
                            Object action = chain.getArg(0);
                            if (action == null) return chain.proceed();

                            String className = action.getClass().getName();

                            // v7.13: 发送 action 类名以 $n 结尾
                            if (className.endsWith("$n")) {
                                Object text = callMethod(action, "getText");
                                if (text instanceof String s && !s.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=v3_action_send text=" + s);
                                    sendNotificationDedup(NOTIFY_TEXT_CMD, "文本指令",
                                            s, chain.getThisObject());
                                }
                                return chain.proceed();
                            }

                            // v8.0+: 尝试 getText()（兼容未来 action 类型）
                            Object text = callMethod(action, "getText");
                            if (text instanceof String s && !s.isEmpty()) {
                                module.log(Log.INFO, TAG,
                                        "event=v3_action_send text=" + s);
                                sendNotificationDedup(NOTIFY_TEXT_CMD, "文本指令",
                                        s, chain.getThisObject());
                            }
                        } catch (Exception ignored) {
                        }
                        return chain.proceed();
                    });

            hookedKeys.put(key, true);
            module.log(Log.INFO, TAG,
                    "event=hook_registered class=" + v3Class.getName() + " method=onAction");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "event=install_failed hook=v3_onaction", t);
        }
    }

    // ------------------------------------------------------------------
    // Hook 2: ASR 语音识别结果
    // ------------------------------------------------------------------

    private void installAsrResultHook(ClassLoader classLoader) {
        Class<?> uiManagerClass = findClass(classLoader,
                "com.xiaomi.voiceassistant.UiManager");
        if (uiManagerClass == null) {
            module.log(Log.WARN, TAG, "event=class_not_found class=UiManager");
            return;
        }

        // 按方法名查找（v7.13 参数 u20.b，v8.0 参数 et0/b，签名不同）
        Method target = findMethodByName(uiManagerClass, "onAsrResult");
        if (target == null) {
            module.log(Log.WARN, TAG,
                    "event=method_not_found class=UiManager method=onAsrResult");
            return;
        }
        target.setAccessible(true);
        module.log(Log.INFO, TAG,
                "event=asr_method_found sig=" + target.getParameterTypes()[0].getName());

        try {
            module.hook(target)
                    .setId("xiaoai_asr_result")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object asrResult = chain.getArg(0);
                            if (asrResult != null) {
                                // 字段名跨版本稳定（query/toDisplay/answer 等）
                                String query = getStringField(asrResult, "query");
                                String toDisplay = getStringField(asrResult, "toDisplay");
                                String answer = getStringField(asrResult, "answer");
                                String answerText = getStringField(asrResult, "answerText");
                                String domain = getStringField(asrResult, "domain");
                                String action = getStringField(asrResult, "action");

                                if (query != null && !query.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=asr_query query=" + query
                                                    + " domain=" + domain
                                                    + " action=" + action);
                                    sendNotificationDedup(NOTIFY_ASR, "语音指令",
                                            query + (domain != null ? " [" + domain + "]" : ""),
                                            chain.getThisObject());
                                }

                                String displayText = firstNonEmpty(toDisplay, answer, answerText);
                                if (displayText != null && !displayText.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=asr_display text=" + displayText);
                                    sendNotificationDedup(NOTIFY_ASR, "返回结果",
                                            displayText, chain.getThisObject());
                                }
                            }
                        } catch (Exception e) {
                            module.log(Log.WARN, TAG, "event=asr_result_hook_error", e);
                        }
                        return chain.proceed();
                    });

            module.log(Log.INFO, TAG, "event=hook_registered method=UiManager.onAsrResult");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "event=install_failed hook=asr_result", t);
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName())) return m;
        }
        return null;
    }

    private static Object callMethod(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getStringField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object val = f.get(obj);
            return val instanceof String ? (String) val : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static Context extractContext(Object thisObj) {
        if (thisObj == null) return null;
        try {
            try {
                Field f = thisObj.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                Object val = f.get(thisObj);
                if (val instanceof Context) return (Context) val;
            } catch (NoSuchFieldException ignored) {
            }

            for (Method m : thisObj.getClass().getMethods()) {
                if ((m.getName().equals("getContext") || m.getName().equals("getApplicationContext"))
                        && m.getParameterCount() == 0
                        && Context.class.isAssignableFrom(m.getReturnType())) {
                    Object ctx = m.invoke(thisObj);
                    if (ctx instanceof Context) return (Context) ctx;
                }
            }

            for (String name : new String[]{"context", "mContext", "mApplication"}) {
                try {
                    Field f = thisObj.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(thisObj);
                    if (val instanceof Context) return (Context) val;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
