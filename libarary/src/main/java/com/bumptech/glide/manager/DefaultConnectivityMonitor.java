package com.bumptech.glide.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * 当有访问网络变化权限的情况下默认使用的连接处理者
 * 稍微注意一下这里还是通过CONNECTIVITY_ACTION广播处理
 * 但是在Android7.0之后这个已经被屏蔽，所以最好在7.0之后通过callback处理
 * 另外在透明主题的有的情况下不会回调onStart和onStop，这点需要注意
 */
class DefaultConnectivityMonitor implements ConnectivityMonitor {
    private final Context context;
    //在RequestManager中注册的链接回调处理者
    private final ConnectivityListener listener;

    //当前网络是否处于连接状态，主要是用于标记之前的状态
    private boolean isConnected;
    //当前广播是否已经注册
    private boolean isRegistered;
    /**
     * 在Android7.0之前这个在网络连接状态变化的时候会有回调
     */
    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean wasConnected = isConnected;
            isConnected = isConnected(context);
            if (wasConnected != isConnected) {//网络状态变化的时候，会回调注册的监听
                listener.onConnectivityChanged(isConnected);
            }
        }
    };

    public DefaultConnectivityMonitor(Context context, ConnectivityListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 注册广播
     */
    private void register() {
        if (isRegistered) {
            return;
        }

        isConnected = isConnected(context);//先记录当前网络状态
        context.registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        isRegistered = true;
    }

    /**
     * 反注册广播
     */
    private void unregister() {
        if (!isRegistered) {
            return;
        }

        context.unregisterReceiver(connectivityReceiver);
        isRegistered = false;
    }

    /**
     * 获取连接状态
     * @return true当前有活动的网络连接,false无
     */
    private boolean isConnected(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    /**
     * Lifecycle的指定回调
     * 主要是做了合理的开关，在onStart的时候注册，onStop时候反注册
     * 当然这也就是说透明主题的时候打开新的页面，基本就是没有反注册这一步
     */

    @Override
    public void onStart() {
        register();
    }

    @Override
    public void onStop() {
        unregister();
    }

    @Override
    public void onDestroy() {
        // Do nothing.
    }
}
