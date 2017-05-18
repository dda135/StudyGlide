package com.bumptech.glide.manager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.support.v4.app.Fragment;
import android.util.Log;

import com.bumptech.glide.RequestManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A view-less {@link android.support.v4.app.Fragment} used to safely store an
 * {@link com.bumptech.glide.RequestManager} that can be used to start, stop and manage Glide requests started for
 * targets within the fragment or activity this fragment is a child of.
 *
 * @see com.bumptech.glide.manager.RequestManagerFragment
 * @see com.bumptech.glide.manager.RequestManagerRetriever
 * @see com.bumptech.glide.RequestManager
 */
public class SupportRequestManagerFragment extends Fragment {

    private static final String TAG = "SupportRMFragment";

    private RequestManager requestManager;
    private final ActivityFragmentLifecycle lifecycle;
    private final RequestManagerTreeNode requestManagerTreeNode =
            new SupportFragmentRequestManagerTreeNode();
    private final HashSet<SupportRequestManagerFragment> childRequestManagerFragments =
        new HashSet<SupportRequestManagerFragment>();
    private SupportRequestManagerFragment rootRequestManagerFragment;

    public SupportRequestManagerFragment() {
        this(new ActivityFragmentLifecycle());//默认为ActivityFragmentLifecycle
    }

    // For testing only.
    @SuppressLint("ValidFragment")
    public SupportRequestManagerFragment(ActivityFragmentLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Sets the current {@link com.bumptech.glide.RequestManager}.
     *
     * @param requestManager The manager to set.
     */
    public void setRequestManager(RequestManager requestManager) {
        this.requestManager = requestManager;
    }

    ActivityFragmentLifecycle getLifecycle() {
        return lifecycle;
    }

    /**
     * Returns the current {@link com.bumptech.glide.RequestManager} or null if none is set.
     */
    public RequestManager getRequestManager() {
        return requestManager;
    }

    /**
     * Returns the {@link RequestManagerTreeNode} that provides tree traversal methods relative to the associated
     * {@link RequestManager}.
     */
    public RequestManagerTreeNode getRequestManagerTreeNode() {
        return requestManagerTreeNode;
    }

    private void addChildRequestManagerFragment(SupportRequestManagerFragment child) {
        childRequestManagerFragments.add(child);
    }

    private void removeChildRequestManagerFragment(SupportRequestManagerFragment child) {
        childRequestManagerFragments.remove(child);
    }

    /**
     * Returns the set of fragments that this RequestManagerFragment's parent is a parent to. (i.e. our parent is
     * the fragment that we are annotating).
     */
    public Set<SupportRequestManagerFragment> getDescendantRequestManagerFragments() {
        if (rootRequestManagerFragment == null) {
            return Collections.emptySet();
        } else if (rootRequestManagerFragment == this) {//当前是顶层Fragment
            //直接返回之前关联的所有孩子的列表
            return Collections.unmodifiableSet(childRequestManagerFragments);
        } else {
            HashSet<SupportRequestManagerFragment> descendants =
                new HashSet<SupportRequestManagerFragment>();
            //这里直接遍历顶层Fragment的孩子列表
            for (SupportRequestManagerFragment fragment
                : rootRequestManagerFragment.getDescendantRequestManagerFragments()) {
                if (isDescendant(fragment.getParentFragment())) {//参数fragment满足依赖关系，就是为当前Fragment的孩子
                    descendants.add(fragment);
                }
            }
            return Collections.unmodifiableSet(descendants);
        }
    }

    /**
     * Returns true if the fragment is a descendant of our parent.
     */
    private boolean isDescendant(Fragment fragment) {
        //获得当前父Fragment
        Fragment root = this.getParentFragment();
        while (fragment.getParentFragment() != null) {
            //如果参数fragment为当前Fragment的孩子，则认为有依赖关系
            if (fragment.getParentFragment() == root) {
                return true;
            }
            //递归向上
            fragment = fragment.getParentFragment();
        }
        return false;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        //onAttach可能会在Activity被销毁之后回调，这个时候要做好异常处理
        try {
            //获得顶层Fragment
            //注意在这个时候的FragmentManager应该已经添加当前指定的Fragment成功
            //所以这里都是通过击中缓存的形式获得
            rootRequestManagerFragment = RequestManagerRetriever.get()
                    .getSupportRequestManagerFragment(getActivity().getSupportFragmentManager());
            if (rootRequestManagerFragment != this) {//当前不是顶层Fragment,添加到顶层Fragment的孩子列表当中
                rootRequestManagerFragment.addChildRequestManagerFragment(this);
            }
        } catch (IllegalStateException e) {
            // OnAttach can be called after the activity is destroyed, see #497.
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Unable to register fragment with root", e);
            }
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (rootRequestManagerFragment != null) {
            rootRequestManagerFragment.removeChildRequestManagerFragment(this);
            rootRequestManagerFragment = null;
        }
    }

    private class SupportFragmentRequestManagerTreeNode implements RequestManagerTreeNode {
        @Override
        public Set<RequestManager> getDescendants() {
            //获得当前孩子列表
            Set<SupportRequestManagerFragment> descendantFragments = getDescendantRequestManagerFragments();
            HashSet<RequestManager> descendants = new HashSet<RequestManager>(descendantFragments.size());
            for (SupportRequestManagerFragment fragment : descendantFragments) {
                if (fragment.getRequestManager() != null) {//要求有添加RequestManager的指定Fragment
                    descendants.add(fragment.getRequestManager());
                }
            }
            //实际上就是当前Fragment的那些有RequestManager的孩子Fragment
            return descendants;
        }
    }

    /**
     * 以下是回调Lifecycle的指定方法
     */

    @Override
    public void onStart() {
        super.onStart();
        lifecycle.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        lifecycle.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycle.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // If an activity is re-created, onLowMemory may be called before a manager is ever set.
        // See #329.
        // 如果一个Activity在回收之后重新创建，这个方法也许会在RequestManager创建之前被回调，所以要注意一下
        if (requestManager != null) {
            requestManager.onLowMemory();
        }
    }

}
