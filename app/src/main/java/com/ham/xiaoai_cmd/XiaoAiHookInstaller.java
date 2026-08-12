package com.ham.xiaoai_cmd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 小米语音助手 Hook 安装器。
 *
 * 基于 APK v7.13.32.0016 静态分析，采用多策略回退应对混淆变化：
 *
 * Hook 1 — 文本输入发送：
 *   接口 com.xiaomi.voiceassistant.mainui.inputmodule.c 定义 onSendClick(String, String)V
 *   实现类 gf0.d0（混淆名，版本间会变化）
 *   策略：按方法名+签名查找实现类，逐个尝试
 *
 * Hook 2 — ASR 语音识别结果：
 *   UiManager.onAsrResult(u20.b)V — UiManager 是稳定单例类
 *   u20.b 对象包含字段：query, toDisplay, answer, answerText, toSpeak, domain, action
 *   策略：直接 Hook UiManager，通过反射读取结果对象字段
 *
 * Hook 3 — 返回结果展示：
 *   无独立 showResult 方法，结果数据在 ASR 回调中已包含
 *   策略：在 onAsrResult 中同时捕获 toDisplay/answer，无需额外 Hook
 *
 * 性能优化：静态缓存已解析的 Class/Method，避免冷启动时重复搜索。
 */
final class XiaoAiHookInstaller {
    private static final String TAG = "XiaoAiCmd";

    private static final String CHANNEL_ID = "automation_cmd";
    private static final String CHANNEL_NAME = "自动化指令";

    private static final int NOTIFY_TEXT_CMD = 1001;
    private static final int NOTIFY_ASR = 1002;
    private static final int NOTIFY_RESULT = 1003;

    // 文本发送 Hook 的候选类名（按优先级排列，混淆名版本间会变化）
    private static final String[] TEXT_SEND_CANDIDATES = {
            "gf0.d0",                                              // v7.13.x 当前版本
            "com.xiaomi.voiceassistant.ConversationFragment$d",    // 稳定内部类
    };

    // ------------------------------------------------------------------
    // 静态缓存 — 同一进程内跨安装调用复用，避免重复反射搜索
    // ------------------------------------------------------------------

    /** 类名 → Class 对象缓存 */
    private static final ConcurrentHashMap<String, Class<?>> classCache =
            new ConcurrentHashMap<>();

    /** 缓存 ActivityThread.currentApplication 方法，避免每次查找 */
    private static volatile Method cachedCurrentAppMethod;

    private final XposedModule module;
    private volatile boolean channelCreated;
    private volatile Context cachedContext;

    XiaoAiHookInstaller(XposedModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------
    // 类查找缓存
    // ------------------------------------------------------------------

    /**
     * 带缓存的类查找。首次查找后结果存入静态 Map，后续调用直接命中。
     */
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

    // ------------------------------------------------------------------
    // Context 获取 — 三级回退
    // ------------------------------------------------------------------

    private Context getContext(Object thisObj) {
        // 1. 已缓存
        if (cachedContext != null) return cachedContext;
        // 2. 从当前对象提取
        Context ctx = extractContext(thisObj);
        if (ctx != null) {
            cachedContext = ctx;
            return ctx;
        }
        // 3. 通过 ActivityThread 获取宿主 App 的 Application Context
        ctx = getApplicationFromActivityThread();
        if (ctx != null) {
            cachedContext = ctx;
            return ctx;
        }
        return null;
    }

    private static Context getApplicationFromActivityThread() {
        try {
            // 缓存 Method 对象，避免每次反射查找
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

    // ------------------------------------------------------------------
    // 安装所有 Hook
    // ------------------------------------------------------------------

    void installAll(ClassLoader classLoader) {
        installTextSendHook(classLoader);
        installAsrResultHook(classLoader);
    }

    // ------------------------------------------------------------------
    // Hook 1: 文本输入发送 — onSendClick(String, String)V
    //
    // 接口定义：com.xiaomi.voiceassistant.mainui.inputmodule.c
    // 策略：遍历候选类名，找到第一个可用的实现类进行 Hook
    // ------------------------------------------------------------------

    private void installTextSendHook(ClassLoader classLoader) {
        for (String className : TEXT_SEND_CANDIDATES) {
            try {
                Class<?> clazz = findClass(classLoader, className);
                if (clazz == null) {
                    module.log(Log.DEBUG, TAG, "event=class_not_found class=" + className);
                    continue;
                }
                Method target = findMethodByName(clazz, "onSendClick");
                if (target == null) {
                    module.log(Log.DEBUG, TAG,
                            "event=method_not_found class=" + className + " method=onSendClick");
                    continue;
                }
                target.setAccessible(true);

                module.hook(target)
                        .setId("xiaoai_text_send")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> {
                            try {
                                // onSendClick(String text, String extra)
                                Object arg0 = chain.getArg(0);
                                if (arg0 instanceof String text && !text.isEmpty()) {
                                    module.log(Log.INFO, TAG, "event=text_send text=" + text);
                                    Context ctx = getContext(chain.getThisObject());
                                    if (ctx != null) {
                                        sendNotification(ctx, "文本指令", text, NOTIFY_TEXT_CMD);
                                    }
                                }
                            } catch (Exception e) {
                                module.log(Log.WARN, TAG, "event=text_send_hook_error", e);
                            }
                            return chain.proceed();
                        });

                module.log(Log.INFO, TAG,
                        "event=hook_registered class=" + className + " method=onSendClick");
                return; // 成功注册，退出循环
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "event=install_failed class=" + className + " hook=text_send", t);
            }
        }
        module.log(Log.WARN, TAG,
                "event=all_candidates_failed hook=text_send — 请检查类名是否随版本变化");
    }

    // ------------------------------------------------------------------
    // Hook 2+3: ASR 语音识别结果 & 返回结果 — UiManager.onAsrResult(u20.b)V
    //
    // UiManager 是稳定单例类，不被混淆
    // u20.b 是 ASR 结果对象，包含 query/toDisplay/answer/answerText 等字段
    // 在此 Hook 中同时捕获语音指令和返回结果，无需额外 Hook
    // ------------------------------------------------------------------

    private void installAsrResultHook(ClassLoader classLoader) {
        try {
            Class<?> uiManagerClass = findClass(classLoader,
                    "com.xiaomi.voiceassistant.UiManager");
            if (uiManagerClass == null) {
                module.log(Log.WARN, TAG, "event=class_not_found class=UiManager");
                return;
            }
            Method target = findMethodByName(uiManagerClass, "onAsrResult");
            if (target == null) {
                module.log(Log.WARN, TAG,
                        "event=method_not_found class=UiManager method=onAsrResult");
                return;
            }
            target.setAccessible(true);

            module.hook(target)
                    .setId("xiaoai_asr_result")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object asrResult = chain.getArg(0);
                            if (asrResult != null) {
                                // 提取 ASR 结果字段（通过反射，兼容字段名变化）
                                String query = getStringField(asrResult, "query");
                                String toDisplay = getStringField(asrResult, "toDisplay");
                                String answer = getStringField(asrResult, "answer");
                                String answerText = getStringField(asrResult, "answerText");
                                String domain = getStringField(asrResult, "domain");
                                String action = getStringField(asrResult, "action");

                                // 语音指令通知
                                if (query != null && !query.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=asr_query query=" + query
                                                    + " domain=" + domain
                                                    + " action=" + action);
                                    Context ctx = getContext(chain.getThisObject());
                                    if (ctx != null) {
                                        sendNotification(ctx, "语音指令",
                                                query + (domain != null ? " [" + domain + "]" : ""),
                                                NOTIFY_ASR);
                                    }
                                }

                                // 返回结果通知（从 ASR 结果中直接获取，替代不存在的 showResult）
                                String displayText = firstNonEmpty(toDisplay, answer, answerText);
                                if (displayText != null && !displayText.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=asr_display text=" + displayText);
                                    Context ctx = getContext(chain.getThisObject());
                                    if (ctx != null) {
                                        sendNotification(ctx, "返回结果",
                                                displayText, NOTIFY_RESULT);
                                    }
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

    /**
     * 按方法名查找（匹配第一个同名方法，兼容参数类型变化）。
     */
    private static Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName())) return m;
        }
        return null;
    }

    /**
     * 反射读取对象的 String 字段（字段名已知，类型已知）。
     */
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

    /**
     * 返回第一个非空非空串的值。
     */
    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * 从 Hook 的 this 对象中提取 Context。
     * UiManager 持有 mContext 字段，其他类尝试 getContext()/getApplicationContext()。
     */
    private static Context extractContext(Object thisObj) {
        if (thisObj == null) return null;
        try {
            // UiManager 有 mContext 字段
            try {
                Field f = thisObj.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                Object val = f.get(thisObj);
                if (val instanceof Context) return (Context) val;
            } catch (NoSuchFieldException ignored) {
            }

            // 通用：尝试 getContext() / getApplicationContext()
            for (Method m : thisObj.getClass().getMethods()) {
                if ((m.getName().equals("getContext") || m.getName().equals("getApplicationContext"))
                        && m.getParameterCount() == 0
                        && Context.class.isAssignableFrom(m.getReturnType())) {
                    Object ctx = m.invoke(thisObj);
                    if (ctx instanceof Context) return (Context) ctx;
                }
            }

            // 通用：尝试 context / mContext 字段
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
