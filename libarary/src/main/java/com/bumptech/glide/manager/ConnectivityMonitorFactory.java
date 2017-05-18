package com.bumptech.glide.manager;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * A factory class that produces a functional {@link com.bumptech.glide.manager.ConnectivityMonitor} if the application
 * has the {@code android.permission.ACCESS_NETWORK_STATE} permission and a no-op non functional
 * {@link com.bumptech.glide.manager.ConnectivityMonitor} if the app does not have the required permission.
 * 默认的网络连接变化处理工厂
 */
public class ConnectivityMonitorFactory {
    public ConnectivityMonitor build(Context context, ConnectivityMonitor.ConnectivityListener listener) {
        //首先获得查看网络状态的权限
        final int res = context.checkCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE");
        final boolean hasPermission = res == PackageManager.PERMISSION_GRANTED;
        if (hasPermission) {//如果当前有指定权限，使用默认的处理者
            return new DefaultConnectivityMonitor(context, listener);
        } else {//否则不处理网络变化的情况，内部其实就是空处理
            return new NullConnectivityMonitor();
        }
    }
}
