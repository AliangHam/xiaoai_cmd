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
 * 小米语音助手 Hook 安装器 v3.1
 *
 * 参考 BeanShell v3.1 策略，三条文本发送链路 + ASR 语音结果：
 *
 * Hook 1 — 文本输入发送（三条链路）：
 *   A. gf0.d0.onSendClick(String, String)V — 混淆类，当前版本可用
 *   B. gf0.d0.w(String)V — final 汇聚点，子类不可覆盖，最可靠
 *   C. InputModuleViewModelV3.onAction(b.n)V — 未混淆稳定类，终极兜底
 *      用类名判断 action 类型（$n=发送, $f=编辑），避免打字过程抛异常
 *
 * Hook 2 — ASR 语音识别结果：
 *   UiManager.onAsrResult(u20.b)V — 稳定单例类
 *   通过反射调用 getQuery()/getToDisplay()/getAnswer() 方法读取结果
 *
 * 通知优化：文本截断 80 字、去换行、按 notifyId 5 秒去重
 */
final class XiaoAiHookInstaller {
    private static final String TAG = "XiaoAiCmd";

    private static final String CHANNEL_ID = "automation_cmd";
    private static final String CHANNEL_NAME = "自动化指令";

    private static final int NOTIFY_TEXT_CMD = 1001;
    private static final int NOTIFY_ASR = 1002;

    // 文本发送 Hook 的候选混淆类名
    private static final String[] TEXT_SEND_CANDIDATES = {
            "gf0.d0",
            "com.xiaomi.voiceassistant.ConversationFragment$d",
    };

    // InputModuleViewModelV3 — 未混淆稳定类，终极兜底
    private static final String V3_CLASS =
            "com.xiaomi.voiceassistant.mainui.inputmodulev3.InputModuleViewModelV3";

    // ------------------------------------------------------------------
    // 静态缓存 — 同一进程内复用，避免重复反射搜索
    // ------------------------------------------------------------------
    private static final ConcurrentHashMap<String, Class<?>> classCache =
            new ConcurrentHashMap<>();
    private static volatile Method cachedCurrentAppMethod;

    // ------------------------------------------------------------------
    // 去重状态 — 按 notifyId 分开维护，5 秒内同文本不重复
    // ------------------------------------------------------------------
    private volatile String lastText1001 = "";
    private volatile long lastTime1001 = 0;
    private volatile String lastText1002 = "";
    private volatile long lastTime1002 = 0;

    // 已注册的 hook key，避免同一方法重复挂载
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

    // ------------------------------------------------------------------
    // Context 获取 — 三级回退
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
    // 通知 — 静音渠道 + 文本截断 + 按 notifyId 去重
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

    /**
     * 文本截断：去换行 + 限长 80 字。
     */
    private static String trimText(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "...";
        return t;
    }

    /**
     * 按 notifyId 去重发送，5 秒内同文本不重复通知。
     */
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
    // Hook 1: 文本输入发送 — 三条链路
    //
    // 发送链路（v7.13.x）：
    //   InputModuleViewV3 发送按钮 -> InputModuleViewModelV3.onAction(b.n)
    //     -> 接口 c.onSendClick -> ConversationFragment$d.onSendClick
    //     -> super.onSendClick(gf0.d0) -> gf0.d0.w() (final) -> i0.startQuery()
    // ------------------------------------------------------------------

    private void installTextSendHooks(ClassLoader classLoader) {
        // 策略 A+B：混淆类 onSendClick + w（final 汇聚点）
        Class<?> targetClass = null;
        for (String className : TEXT_SEND_CANDIDATES) {
            targetClass = findClass(classLoader, className);
            if (targetClass != null) {
                module.log(Log.INFO, TAG, "event=text_send_class_found class=" + className);
                break;
            }
        }

        if (targetClass != null) {
            hookTextMethod(targetClass, "onSendClick");
            hookTextMethod(targetClass, "w");
        } else {
            module.log(Log.WARN, TAG,
                    "event=all_candidates_failed hook=text_send — 请检查类名是否随版本变化");
        }

        // 策略 C：InputModuleViewModelV3.onAction — 未混淆稳定类，终极兜底
        installV3FallbackHook(classLoader);
    }

    /**
     * Hook 文本发送方法：onSendClick(String,String)V 或 w(String)V。
     * 用 hook key 防止重复挂载。
     */
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
                    .setId("xiaoai_text_" + methodName)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String text && !text.isEmpty()) {
                                module.log(Log.INFO, TAG,
                                        "event=text_send method=" + methodName + " text=" + text);
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
            module.log(Log.INFO, TAG, "event=hook_registered class=" + clazz.getName()
                    + " method=" + methodName);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "event=install_failed class=" + clazz.getName() + " method=" + methodName, t);
        }
    }

    /**
     * 策略 C：InputModuleViewModelV3.onAction — 终极兜底。
     * 用类名后缀判断 action 类型：
     *   b.n → 发送 action（需要捕获）
     *   b.f → 编辑 action（跳过，避免打字过程反复抛异常）
     */
    private void installV3FallbackHook(ClassLoader classLoader) {
        Class<?> v3Class = findClass(classLoader, V3_CLASS);
        if (v3Class == null) {
            module.log(Log.DEBUG, TAG, "event=class_not_found class=InputModuleViewModelV3");
            return;
        }

        String key = V3_CLASS + "#onAction";
        if (hookedKeys.containsKey(key)) return;

        Method target = findMethodByName(v3Class, "onAction");
        if (target == null) {
            module.log(Log.DEBUG, TAG,
                    "event=method_not_found class=InputModuleViewModelV3 method=onAction");
            return;
        }
        target.setAccessible(true);

        try {
            module.hook(target)
                    .setId("xiaoai_v3_onaction")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object action = chain.getArg(0);
                            if (action == null) return chain.proceed();

                            // 类名后缀判断：$n = 发送 action，其他跳过
                            String className = action.getClass().getName();
                            if (!className.endsWith("$n")) return chain.proceed();

                            // 反射调用 getText() 获取发送文本
                            Object text = callMethod(action, "getText");
                            if (text instanceof String s && !s.isEmpty()) {
                                module.log(Log.INFO, TAG,
                                        "event=v3_action_send text=" + s);
                                sendNotificationDedup(NOTIFY_TEXT_CMD, "文本指令",
                                        s, chain.getThisObject());
                            }
                        } catch (Exception ignored) {
                            // 非发送 action，忽略
                        }
                        return chain.proceed();
                    });

            hookedKeys.put(key, true);
            module.log(Log.INFO, TAG, "event=hook_registered method=InputModuleViewModelV3.onAction");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "event=install_failed hook=v3_onaction", t);
        }
    }

    // ------------------------------------------------------------------
    // Hook 2: ASR 语音识别结果 — UiManager.onAsrResult(u20.b)V
    //
    // UiManager 是稳定单例类，不被混淆
    // 通过反射调用 getter 方法读取结果（比字段访问更稳定）
    // ------------------------------------------------------------------

    private void installAsrResultHook(ClassLoader classLoader) {
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

        try {
            module.hook(target)
                    .setId("xiaoai_asr_result")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object asrResult = chain.getArg(0);
                            if (asrResult != null) {
                                // 优先用 getter 方法（比字段访问更稳定）
                                String query = callStringMethod(asrResult, "getQuery");
                                String toDisplay = callStringMethod(asrResult, "getToDisplay");
                                String answer = callStringMethod(asrResult, "getAnswer");
                                String answerText = callStringMethod(asrResult, "getAnswerText");
                                String domain = callStringMethod(asrResult, "getDomain");
                                String action = callStringMethod(asrResult, "getAction");

                                // getter 失败时回退到字段访问
                                if (query == null) query = getStringField(asrResult, "query");
                                if (toDisplay == null) toDisplay = getStringField(asrResult, "toDisplay");
                                if (answer == null) answer = getStringField(asrResult, "answer");
                                if (answerText == null) answerText = getStringField(asrResult, "answerText");
                                if (domain == null) domain = getStringField(asrResult, "domain");
                                if (action == null) action = getStringField(asrResult, "action");

                                // 语音指令通知
                                if (query != null && !query.isEmpty()) {
                                    module.log(Log.INFO, TAG,
                                            "event=asr_query query=" + query
                                                    + " domain=" + domain
                                                    + " action=" + action);
                                    sendNotificationDedup(NOTIFY_ASR, "语音指令",
                                            query + (domain != null ? " [" + domain + "]" : ""),
                                            chain.getThisObject());
                                }

                                // 返回结果通知
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

    /**
     * 反射调用无参方法，返回 String 结果。失败返回 null。
     */
    private static String callStringMethod(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object val = m.invoke(obj);
            return val instanceof String ? (String) val : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反射调用无参方法，返回 Object。失败返回 null。
     */
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
