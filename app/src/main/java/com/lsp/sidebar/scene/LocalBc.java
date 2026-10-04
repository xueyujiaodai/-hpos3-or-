package com.lsp.sidebar.scene;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 进程内广播发送器。
 *
 * 手机管家对“应用列表变更”的感知走的是进程内广播：
 *   P.a.b(context).d(intent)   （混淆类：包 P 下的类 a）
 * 只发送给 remote 子进程内动态注册的接收者（VideoToolBoxService / GameBoosterService）。
 *
 * 模块运行在同一个 remote 子进程，所以可以直接复用它；若该类在后续版本改名，
 * 回退到 androidx 的 LocalBroadcastManager（语义相同）。
 */
public final class LocalBc {

    private static final String TAG = "LspSidebarSwitch.LocalBc";
    private static final String CLS_LOCAL_BC = "P.a";

    public static void send(Context ctx, String action) {
        Intent intent = new Intent(action);
        if (sendViaAppClass(ctx, intent)) {
            return;
        }
        if (sendViaAndroidX(ctx, intent)) {
            return;
        }
        // 双保险：退化为全局广播（仅当两类本地广播都拿不到时）
        try {
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "fallback global broadcast failed", t);
        }
    }

    private static boolean sendViaAppClass(Context ctx, Intent intent) {
        try {
            Object b = XposedHelpersRef.callStatic(CLS_LOCAL_BC, "b", ctx);
            if (b != null) {
                XposedHelpersRef.call(b, "d", intent);
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "P.a not available: " + t.getMessage());
        }
        return false;
    }

    private static boolean sendViaAndroidX(Context ctx, Intent intent) {
        for (String cls : new String[]{
                "androidx.localbroadcastmanager.content.LocalBroadcastManager",
                "androidx.core.content.LocalBroadcastManager"}) {
            try {
                Object mgr = XposedHelpersRef.callStatic(cls, "getInstance", ctx);
                XposedHelpersRef.call(mgr, "sendBroadcast", intent);
                return true;
            } catch (Throwable t) {
                Log.d(TAG, cls + " not available: " + t.getMessage());
            }
        }
        return false;
    }

    /** 轻量反射封装：仅按方法名 + 参数个数调用（适配混淆类名）。 */
    public static final class XposedHelpersRef {

        public static Object callStatic(String clsName, String methodName, Object... args) throws Exception {
            Class<?> cls = Class.forName(clsName);
            Method m = findMethod(cls, methodName, args.length);
            if (m == null) {
                throw new NoSuchMethodException(clsName + "." + methodName);
            }
            m.setAccessible(true);
            return m.invoke(null, args);
        }

        public static Object call(Object target, String methodName, Object... args) throws Exception {
            Method m = findMethod(target.getClass(), methodName, args.length);
            if (m == null) {
                throw new NoSuchMethodException(target.getClass().getName() + "." + methodName);
            }
            m.setAccessible(true);
            return m.invoke(target, args);
        }

        private static Method findMethod(Class<?> cls, String name, int argCount) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                    return m;
                }
            }
            return null;
        }
    }
}
