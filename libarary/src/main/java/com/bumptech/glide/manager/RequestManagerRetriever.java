package com.bumptech.glide.manager;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.util.Log;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.util.Util;

import java.util.HashMap;
import java.util.Map;

/**
 * A collection of static methods for creating new {@link com.bumptech.glide.RequestManager}s or retrieving existing
 * ones from activities and fragment.
 * 一个RequestManager工厂，通过不同来源构建不同的RequestManager
 * 大概说明一下这个工厂的意义，因为Glide可以同步生命周期，
 * 这里会对应不同的RequestManager来处理
 * 另外可以看到API17之后才采用Fragment，从Stack Overflow中可以看到在这之前Fragment的getChildFragmentManager是有一定问题的
 * 不过可以看到Activity的getFragmentManager是没有问题的，这里可能会引起歧义
 * 使用Fragment的时候要注意一下细节，否则默认采用Application级别的RequestManager
 * 另外注意到在子线程调用的时候统一采用的是Application级别的RequestManager
 * 实际上几种场景下面主要是LifeCycle和TreeNode有区别：
 * 1.Application：
 * ApplicationLifecycle-->只会在添加监听的时候回调一次onStart，相当于没有生命周期关联
 * TreeNode-->空列表
 * 2.SupportFragment：
 * ActivityFragmentLifecycle-->有onStart、onStop和onDestroy，实际上内部还有onLowMemory回调RequestManager清理
 * TreeNode-->子Fragment列表
 * 3.Fragment：
 * 基本同SupportFragment，多了个onTrimMemory回调RequestManager
 * TreeNode-->子Fragment列表
 */
public class RequestManagerRetriever implements Handler.Callback {
    private static final String TAG = "RMRetriever";
    static final String FRAGMENT_TAG = "com.bumptech.glide.manager";

    private static final int ID_REMOVE_FRAGMENT_MANAGER = 1;
    private static final int ID_REMOVE_SUPPORT_FRAGMENT_MANAGER = 2;

    /**
     * The top application level RequestManager.
     * 因为RequestManagerRetriever本身是单例工厂，所以在同一个进程段中所有的Glide只会有一个专门处理
     * 非Activity和Fragment的Context级别的相关请求（ApplicationContext、Service之类）
     * */
    private volatile RequestManager applicationManager;

    // Visible for testing.
    /** Pending adds for RequestManagerFragments. */
    final Map<android.app.FragmentManager, RequestManagerFragment> pendingRequestManagerFragments =
            new HashMap<android.app.FragmentManager, RequestManagerFragment>();

    // Visible for testing.
    /** Pending adds for SupportRequestManagerFragments. */
    final Map<FragmentManager, SupportRequestManagerFragment> pendingSupportRequestManagerFragments =
            new HashMap<FragmentManager, SupportRequestManagerFragment>();

    /** Main thread handler to handle cleaning up pending fragment maps. */
    private final Handler handler;

    /**
     * Retrieves and returns the RequestManagerRetriever singleton.
     */
    //单例
    private static final RequestManagerRetriever INSTANCE = new RequestManagerRetriever();
    public static RequestManagerRetriever get() {
        return INSTANCE;
    }
    RequestManagerRetriever() {
        //这里初始化了一个主线程的Handler，并且在类中实现了handleMessage
        handler = new Handler(Looper.getMainLooper(), this /* Callback */);
    }

    /**
     * Glide中的默认提供
     * 子线程默认使用Application级别的RequestManager
     * 内部基于不同的Context来区分实际的RequestManager
     */
    public RequestManager get(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("You cannot start a load on a null Context");
        } else if (Util.isOnMainThread() && !(context instanceof Application)) {
            if (context instanceof FragmentActivity) {
                return get((FragmentActivity) context);
            } else if (context instanceof Activity) {
                return get((Activity) context);
            } else if (context instanceof ContextWrapper) {
                return get(((ContextWrapper) context).getBaseContext());
            }
        }
        //子线程默认使用Application级别的RequestManager
        return getApplicationManager(context);
    }

    /**
     * 尝试获取v4Fragment级别的RequestManager，为了同步生命周期
     * 注意如果是在子线程调用的话，默认使用的是ApplicationContext级别的RequestManager
     */
    public RequestManager get(Fragment fragment) {
        if (fragment.getActivity() == null) {
            throw new IllegalArgumentException("You cannot start a load on a fragment before it is attached");
        }
        if (Util.isOnBackgroundThread()) {
            //子线程的话默认采用Application级别的RequestManager
            return get(fragment.getActivity().getApplicationContext());
        } else {
            FragmentManager fm = fragment.getChildFragmentManager();
            return supportFragmentGet(fragment.getActivity(), fm);
        }
    }

    /**
     * 尝试获取v4FragmentActivity级别的RequestManager，为了同步生命周期
     * 注意如果是在子线程调用的话，默认使用的是ApplicationContext级别的RequestManager
     */
    public RequestManager get(FragmentActivity activity) {
        //这里通过Looper校验当前线程
        if (Util.isOnBackgroundThread()) {
            //子线程采用Application级别的RequestManager
            return get(activity.getApplicationContext());
        } else {
            assertNotDestroyed(activity);//校验Activity是否销毁
            //获得当前v4的FragmentManager
            FragmentManager fm = activity.getSupportFragmentManager();
            return supportFragmentGet(activity, fm);
        }
    }

    /**
     * 非Activity和Fragment的Context的级别的RequestManager，简称Application级别的RequestManager
     */
    private RequestManager getApplicationManager(Context context) {
        // Either an application context or we're on a background thread.
        //注意到明显的单例
        if (applicationManager == null) {
            synchronized (this) {
                if (applicationManager == null) {
                    // Normally pause/resume is taken care of by the fragment we add to the fragment or activity.
                    // However, in this case since the manager attached to the application will not receive lifecycle
                    // events, we must force the manager to start resumed using ApplicationLifecycle.
                    // ApplicationLifecycle只会在添加监听的时候回调onStart，简单说就是只有启动没有关闭和暂停
                    // 至于RequestManager子节点则必然为空
                    // 这个和Application在整个应用运行时有关，也很合理
                    applicationManager = new RequestManager(context.getApplicationContext(),
                            new ApplicationLifecycle(), new EmptyRequestManagerTreeNode());
                }
            }
        }

        return applicationManager;
    }

    /**
     * 获得v4FragmentManager对应的RequestManager
     * 使用场景为FragmentActivity、v4Fragment
     */
    RequestManager supportFragmentGet(Context context, FragmentManager fm) {
        //将指定的Fragment添加到当前FragmentManager
        SupportRequestManagerFragment current = getSupportRequestManagerFragment(fm);
        //尝试关联指定的RequestManager
        RequestManager requestManager = current.getRequestManager();
        if (requestManager == null) {
            //这里关注一下Lifecycle和RequestManagerTreeNode即可，实际上不同情况下的RequestManager本质上就这个有区别
            requestManager = new RequestManager(context, current.getLifecycle(), current.getRequestManagerTreeNode());
            //关联RequestManager，用于回调指定操作
            current.setRequestManager(requestManager);
        }
        return requestManager;
    }

    /**
     * 在制定的v4FragmentManager中添加指定的Fragment用于同步生命周期
     */
    SupportRequestManagerFragment getSupportRequestManagerFragment(final FragmentManager fm) {
        //尝试从FragmentManager获取指定埋入的Fragment，简单说就是通过这个没有UI的Fragment实现的生命周期同步
        SupportRequestManagerFragment current = (SupportRequestManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            //要尝试埋入指定的Fragment
            //因为commitAllowingStateLoss会通过post的形式进入主线程队列中等待执行
            //这里通过pendingSupportRequestManagerFragments来防止在一个post过程中的重复添加
            //从意义上来说我觉得就是保证一次逻辑上的正常添加
            //因为add操作实际上可以重复添加
            current = pendingSupportRequestManagerFragments.get(fm);
            if (current == null) {
                //当前没有添加任务，尝试新建一个添加任务
                //新建一个支持RequestManager的Fragment，用于在指定生命周期回调指定的操作
                current = new SupportRequestManagerFragment();
                //标记当前任务开始
                pendingSupportRequestManagerFragments.put(fm, current);
                //将指定Fragment添加到FragmentManager当中
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();
                //注意到这个添加到队列中的任务一定在commitAllowingStateLoss之后
                handler.obtainMessage(ID_REMOVE_SUPPORT_FRAGMENT_MANAGER, fm).sendToTarget();
            }
        }
        return current;
    }


    /**
     * 当使用对象为Activity级别的时候，会得到指定的RequestManager以用于同步生命周期
     * 注意如果是在子线程调用的话，默认使用的是ApplicationContext级别的RequestManager
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public RequestManager get(Activity activity) {
        if (Util.isOnBackgroundThread() || Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            //子线程采用Application级别的RequestManager
            return get(activity.getApplicationContext());
        } else {
            assertNotDestroyed(activity);//校验Activity是否已经销毁
            android.app.FragmentManager fm = activity.getFragmentManager();
            return fragmentGet(activity, fm);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private static void assertNotDestroyed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            throw new IllegalArgumentException("You cannot start a load for a destroyed activity");
        }
    }

    /**
     * 当使用对象为Fragment级别的时候，会得到指定的RequestManager以用于同步生命周期
     * 注意如果是在子线程调用的话，默认使用的是ApplicationContext级别的RequestManager
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public RequestManager get(android.app.Fragment fragment) {
        if (fragment.getActivity() == null) {
            throw new IllegalArgumentException("You cannot start a load on a fragment before it is attached");
        }
        if (Util.isOnBackgroundThread() || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return get(fragment.getActivity().getApplicationContext());
        } else {
            android.app.FragmentManager fm = fragment.getChildFragmentManager();
            return fragmentGet(fragment.getActivity(), fm);
        }
    }

    /**
     * 注意到这里标注了API17，然而实际上Activity的getFragmentManager可以正常使用
     * 这个主要针对的是Fragment.getChildFragmentManager
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    RequestManagerFragment getRequestManagerFragment(final android.app.FragmentManager fm) {
        //尝试从缓存中获取指定埋入的Fragment
        RequestManagerFragment current = (RequestManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            //commit不是瞬间完成的，要发送到主线程Handler的任务队列中等待执行，所以这里先通过一个Map记录
            current = pendingRequestManagerFragments.get(fm);
            if (current == null) {//没有在任务队列中
                current = new RequestManagerFragment();//新建一个指定的Fragment
                pendingRequestManagerFragments.put(fm, current);//记录commit当前已经添加到任务队列中
                //添加指定Fragment到fm里面，但是不绘制UI
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();
                //发送一个消息给handler，此时commit队列已经添加完成，可以在主线程Handler的下一个任务中清理pendingRequestManagerFragments
                handler.obtainMessage(ID_REMOVE_FRAGMENT_MANAGER, fm).sendToTarget();
            }
        }
        return current;
    }

    /**
     * 获得不同Activity和API>17时候的Fragment对应的RequestManager
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    RequestManager fragmentGet(Context context, android.app.FragmentManager fm) {
        RequestManagerFragment current = getRequestManagerFragment(fm);
        RequestManager requestManager = current.getRequestManager();
        if (requestManager == null) {//设置RequestManager关联默默添加的Fragment，从而起到同步生命周期的效果
            requestManager = new RequestManager(context, current.getLifecycle(), current.getRequestManagerTreeNode());
            current.setRequestManager(requestManager);
        }
        return requestManager;
    }

    @Override
    public boolean handleMessage(Message message) {
        boolean handled = true;
        Object removed = null;
        Object key = null;
        //实际上就是从等待中队列清除，意味着接下来可以再次添加指定的Fragment
        //这个会在commit之后执行，那么在MessageQueue中一定在commit之后，个人觉得相当于一次commit的锁
        switch (message.what) {
            case ID_REMOVE_FRAGMENT_MANAGER:
                android.app.FragmentManager fm = (android.app.FragmentManager) message.obj;
                key = fm;
                removed = pendingRequestManagerFragments.remove(fm);
                break;
            case ID_REMOVE_SUPPORT_FRAGMENT_MANAGER://v4
                FragmentManager supportFm = (FragmentManager) message.obj;
                key = supportFm;
                removed = pendingSupportRequestManagerFragments.remove(supportFm);
                break;
            default:
                handled = false;
        }
        if (handled && removed == null && Log.isLoggable(TAG, Log.WARN)) {
            Log.w(TAG, "Failed to remove expected request manager fragment, manager: " + key);
        }
        return handled;
    }
}
