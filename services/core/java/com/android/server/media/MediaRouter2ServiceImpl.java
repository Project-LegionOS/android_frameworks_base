/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.media;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;
import static android.media.MediaRouter2Utils.getOriginalId;
import static android.media.MediaRouter2Utils.getProviderId;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.MediaRouter2Manager;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Implements features related to {@link android.media.MediaRouter2} and
 * {@link android.media.MediaRouter2Manager}.
 */
class MediaRouter2ServiceImpl {
    private static final String TAG = "MR2ServiceImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // TODO: (In Android S or later) if we add callback methods for generic failures
    //       in MediaRouter2, remove this constant and replace the usages with the real request IDs.
    private static final long DUMMY_REQUEST_ID = -1;
    private static final int PACKAGE_IMPORTANCE_FOR_DISCOVERY = IMPORTANCE_FOREGROUND_SERVICE;

    private final Context mContext;
    private final UserManagerInternal mUserManagerInternal;
    private final Object mLock = new Object();
    final AtomicInteger mNextRouterOrManagerId = new AtomicInteger(1);
    final ActivityManager mActivityManager;
    final PowerManager mPowerManager;

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, RouterRecord> mAllRouterRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentActiveUserId = -1;

    private final ActivityManager.OnUidImportanceListener mOnUidImportanceListener =
            (uid, importance) -> {
                synchronized (mLock) {
                    final int count = mUserRecords.size();
                    for (int i = 0; i < count; i++) {
                        mUserRecords.valueAt(i).mHandler.maybeUpdateDiscoveryPreferenceForUid(uid);
                    }
                }
            };

    private final BroadcastReceiver mScreenOnOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                final int count = mUserRecords.size();
                for (int i = 0; i < count; i++) {
                    UserHandler userHandler = mUserRecords.valueAt(i).mHandler;
                    userHandler.sendMessage(PooledLambda.obtainMessage(
                            UserHandler::updateDiscoveryPreferenceOnHandler, userHandler));
                }
            }
        }
    };

    /* package */ MediaRouter2ServiceImpl(Context context) {
        mContext = context;
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mActivityManager.addOnUidImportanceListener(mOnUidImportanceListener,
                PACKAGE_IMPORTANCE_FOR_DISCOVERY);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);

        IntentFilter screenOnOffIntentFilter = new IntentFilter();
        screenOnOffIntentFilter.addAction(ACTION_SCREEN_ON);
        screenOnOffIntentFilter.addAction(ACTION_SCREEN_OFF);

        mContext.registerReceiver(mScreenOnOffReceiver, screenOnOffIntentFilter);
    }

    ////////////////////////////////////////////////////////////////
    ////  Calls from MediaRouter2
    ////   - Should not have @NonNull/@Nullable on any arguments
    ////////////////////////////////////////////////////////////////

    @NonNull
    public void enforceMediaContentControlPermission() {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();

        try {
            mContext.enforcePermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid,
                    "Must hold MEDIA_CONTENT_CONTROL permission.");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    public List<MediaRoute2Info> getSystemRoutes() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasModifyAudioRoutingPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                == PackageManager.PERMISSION_GRANTED;

        final long token = Binder.clearCallingIdentity();
        try {
            Collection<MediaRoute2Info> systemRoutes;
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                if (hasModifyAudioRoutingPermission) {
                    MediaRoute2ProviderInfo providerInfo =
                            userRecord.mHandler.mSystemProvider.getProviderInfo();
                    if (providerInfo != null) {
                        systemRoutes = providerInfo.getRoutes();
                    } else {
                        systemRoutes = Collections.emptyList();
                    }
                } else {
                    systemRoutes = new ArrayList<>();
                    systemRoutes.add(userRecord.mHandler.mSystemProvider.getDefaultRoute());
                }
            }
            return new ArrayList<>(systemRoutes);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    public RoutingSessionInfo getSystemSessionInfo(
            @Nullable String packageName, boolean setDeviceRouteSelected) {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasModifyAudioRoutingPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                == PackageManager.PERMISSION_GRANTED;

        final long token = Binder.clearCallingIdentity();
        try {
            RoutingSessionInfo systemSessionInfo = null;
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                List<RoutingSessionInfo> sessionInfos;
                if (hasModifyAudioRoutingPermission) {
                    if (setDeviceRouteSelected) {
                        systemSessionInfo = userRecord.mHandler.mSystemProvider
                                .generateDeviceRouteSelectedSessionInfo(packageName);
                    } else {
                        sessionInfos = userRecord.mHandler.mSystemProvider.getSessionInfos();
                        if (sessionInfos != null && !sessionInfos.isEmpty()) {
                            systemSessionInfo = new RoutingSessionInfo.Builder(sessionInfos.get(0))
                                    .setClientPackageName(packageName).build();
                        } else {
                            Slog.w(TAG, "System provider does not have any session info.");
                        }
                    }
                } else {
                    systemSessionInfo = new RoutingSessionInfo.Builder(
                            userRecord.mHandler.mSystemProvider.getDefaultSessionInfo())
                            .setClientPackageName(packageName).build();
                }
            }
            return systemSessionInfo;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerRouter2(IMediaRouter2 router, String packageName) {
        Objects.requireNonNull(router, "router must not be null");
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName must not be empty");
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasConfigureWifiDisplayPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                == PackageManager.PERMISSION_GRANTED;
        final boolean hasModifyAudioRoutingPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                == PackageManager.PERMISSION_GRANTED;

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerRouter2Locked(router, uid, pid, packageName, userId,
                        hasConfigureWifiDisplayPermission, hasModifyAudioRoutingPermission);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterRouter2(IMediaRouter2 router) {
        Objects.requireNonNull(router, "router must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterRouter2Locked(router, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setDiscoveryRequestWithRouter2(IMediaRouter2 router,
            RouteDiscoveryPreference preference) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                RouterRecord routerRecord = mAllRouterRecords.get(router.asBinder());
                if (routerRecord == null) {
                    Slog.w(TAG, "Ignoring updating discoveryRequest of null routerRecord.");
                    return;
                }
                setDiscoveryRequestWithRouter2Locked(routerRecord, preference);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithRouter2(IMediaRouter2 router,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setRouteVolumeWithRouter2Locked(router, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateSessionWithRouter2(IMediaRouter2 router, int requestId,
            long managerRequestId, RoutingSessionInfo oldSession,
            MediaRoute2Info route, Bundle sessionHints) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(oldSession, "oldSession must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithRouter2Locked(requestId, managerRequestId,
                        router, oldSession, route, sessionHints);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void deselectRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                deselectRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void transferToRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            int volume) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(uniqueSessionId, "uniqueSessionId must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSessionVolumeWithRouter2Locked(router, uniqueSessionId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void releaseSessionWithRouter2(IMediaRouter2 router, String uniqueSessionId) {
        Objects.requireNonNull(router, "router must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                releaseSessionWithRouter2Locked(router, uniqueSessionId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    ////////////////////////////////////////////////////////////////
    ////  Calls from MediaRouter2Manager
    ////   - Should not have @NonNull/@Nullable on any arguments
    ////////////////////////////////////////////////////////////////

    @NonNull
    public List<RoutingSessionInfo> getRemoteSessions(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                return getRemoteSessionsLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerManager(IMediaRouter2Manager manager, String packageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName must not be empty");
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(manager, uid, pid, packageName, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterManager(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterManagerLocked(manager, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void startScan(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                startScanLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void stopScan(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                stopScanLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setRouteVolumeWithManagerLocked(requestId, manager, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateSessionWithManager(IMediaRouter2Manager manager, int requestId,
            RoutingSessionInfo oldSession, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(oldSession, "oldSession must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithManagerLocked(requestId, manager, oldSession, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void deselectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                deselectRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void transferToRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSessionVolumeWithManagerLocked(requestId, manager, uniqueSessionId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void releaseSessionWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                releaseSessionWithManagerLocked(requestId, manager, uniqueSessionId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* package */ void updateRunningUserAndProfiles(int newActiveUserId) {
        synchronized (mLock) {
            if (mCurrentActiveUserId != newActiveUserId) {
                Slog.i(TAG, TextUtils.formatSimple(
                        "switchUser | user: %d", newActiveUserId));

                mCurrentActiveUserId = newActiveUserId;
                for (int i = 0; i < mUserRecords.size(); i++) {
                    int userId = mUserRecords.keyAt(i);
                    UserRecord userRecord = mUserRecords.valueAt(i);
                    if (isUserActiveLocked(userId)) {
                        // userId corresponds to the active user, or one of its profiles. We
                        // ensure the associated structures are initialized.
                        userRecord.mHandler.sendMessage(
                                obtainMessage(UserHandler::start, userRecord.mHandler));
                    } else {
                        userRecord.mHandler.sendMessage(
                                obtainMessage(UserHandler::stop, userRecord.mHandler));
                        disposeUserIfNeededLocked(userRecord);
                    }
                }
            }
        }
    }

    void routerDied(@NonNull RouterRecord routerRecord) {
        synchronized (mLock) {
            unregisterRouter2Locked(routerRecord.mRouter, true);
        }
    }

    void managerDied(@NonNull ManagerRecord managerRecord) {
        synchronized (mLock) {
            unregisterManagerLocked(managerRecord.mManager, true);
        }
    }

    /**
     * Returns {@code true} if the given {@code userId} corresponds to the active user or a profile
     * of the active user, returns {@code false} otherwise.
     */
    @GuardedBy("mLock")
    private boolean isUserActiveLocked(int userId) {
        return mUserManagerInternal.getProfileParentId(userId) == mCurrentActiveUserId;
    }

    ////////////////////////////////////////////////////////////////
    ////  ***Locked methods related to MediaRouter2
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////////

    @GuardedBy("mLock")
    private void registerRouter2Locked(@NonNull IMediaRouter2 router, int uid, int pid,
            @NonNull String packageName, int userId, boolean hasConfigureWifiDisplayPermission,
            boolean hasModifyAudioRoutingPermission) {
        final IBinder binder = router.asBinder();
        if (mAllRouterRecords.get(binder) != null) {
            Slog.w(TAG, "registerRouter2Locked: Same router already exists. packageName="
                    + packageName);
            return;
        }

        UserRecord userRecord = getOrCreateUserRecordLocked(userId);
        RouterRecord routerRecord = new RouterRecord(userRecord, router, uid, pid, packageName,
                hasConfigureWifiDisplayPermission, hasModifyAudioRoutingPermission);
        try {
            binder.linkToDeath(routerRecord, 0);
        } catch (RemoteException ex) {
            throw new RuntimeException("MediaRouter2 died prematurely.", ex);
        }

        userRecord.mRouterRecords.add(routerRecord);
        mAllRouterRecords.put(binder, routerRecord);

        userRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyRouterRegistered,
                        userRecord.mHandler, routerRecord));

        Slog.i(TAG, TextUtils.formatSimple(
                "registerRouter2 | package: %s, uid: %d, pid: %d, router: %d",
                packageName, uid, pid, routerRecord.mRouterId));
    }

    @GuardedBy("mLock")
    private void unregisterRouter2Locked(@NonNull IMediaRouter2 router, boolean died) {
        RouterRecord routerRecord = mAllRouterRecords.remove(router.asBinder());
        if (routerRecord == null) {
            Slog.w(TAG, "Ignoring unregistering unknown router2");
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "unregisterRouter2 | package: %s, router: %d",
                routerRecord.mPackageName,
                routerRecord.mRouterId));

        UserRecord userRecord = routerRecord.mUserRecord;
        userRecord.mRouterRecords.remove(routerRecord);
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyDiscoveryPreferenceChangedToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName, null));
        userRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        userRecord.mHandler));
        routerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since router removed from user
    }

    private void setDiscoveryRequestWithRouter2Locked(@NonNull RouterRecord routerRecord,
            @NonNull RouteDiscoveryPreference discoveryRequest) {
        if (routerRecord.mDiscoveryPreference.equals(discoveryRequest)) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setDiscoveryRequestWithRouter2 | router: %d, discovery request: %s",
                routerRecord.mRouterId, discoveryRequest.toString()));

        routerRecord.mDiscoveryPreference = discoveryRequest;
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyDiscoveryPreferenceChangedToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName,
                        routerRecord.mDiscoveryPreference));
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        routerRecord.mUserRecord.mHandler));
    }

    private void setRouteVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord != null) {
            Slog.i(TAG, TextUtils.formatSimple(
                    "setRouteVolumeWithRouter2 | router: %d, volume: %d",
                    routerRecord.mRouterId, volume));

            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::setRouteVolumeOnHandler,
                            routerRecord.mUserRecord.mHandler,
                            DUMMY_REQUEST_ID, route, volume));
        }
    }

    private void requestCreateSessionWithRouter2Locked(int requestId, long managerRequestId,
            @NonNull IMediaRouter2 router, @NonNull RoutingSessionInfo oldSession,
            @NonNull MediaRoute2Info route, @Nullable Bundle sessionHints) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        if (managerRequestId != MediaRoute2ProviderService.REQUEST_ID_NONE) {
            ManagerRecord manager = routerRecord.mUserRecord.mHandler.findManagerWithId(
                    toRequesterId(managerRequestId));
            if (manager == null || manager.mLastSessionCreationRequest == null) {
                Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                        + "Ignoring unknown request.");
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
            if (!TextUtils.equals(manager.mLastSessionCreationRequest.mOldSession.getId(),
                    oldSession.getId())) {
                Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                        + "Ignoring unmatched routing session.");
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
            if (!TextUtils.equals(manager.mLastSessionCreationRequest.mRoute.getId(),
                    route.getId())) {
                // When media router has no permission
                if (!routerRecord.mHasModifyAudioRoutingPermission
                        && manager.mLastSessionCreationRequest.mRoute.isSystemRoute()
                        && route.isSystemRoute()) {
                    route = manager.mLastSessionCreationRequest.mRoute;
                } else {
                    Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                            + "Ignoring unmatched route.");
                    routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                            routerRecord, requestId);
                    return;
                }
            }
            manager.mLastSessionCreationRequest = null;
        } else {
            if (route.isSystemRoute() && !routerRecord.mHasModifyAudioRoutingPermission
                    && !TextUtils.equals(route.getId(),
                    routerRecord.mUserRecord.mHandler.mSystemProvider.getDefaultRoute().getId())) {
                Slog.w(TAG, "MODIFY_AUDIO_ROUTING permission is required to transfer to"
                        + route);
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
        }

        long uniqueRequestId = toUniqueRequestId(routerRecord.mRouterId, requestId);
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::requestCreateSessionWithRouter2OnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId, managerRequestId, routerRecord, oldSession, route,
                        sessionHints));
    }

    private void selectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "selectRouteWithRouter2 | router: %d, route: %s",
                routerRecord.mRouterId, route.getId()));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    private void deselectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "deselectRouteWithRouter2 | router: %d, route: %s",
                routerRecord.mRouterId, route.getId()));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    private void transferToRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "transferToRouteWithRouter2 | router: %d, route: %s",
                routerRecord.mRouterId, route.getId()));

        String defaultRouteId =
                routerRecord.mUserRecord.mHandler.mSystemProvider.getDefaultRoute().getId();
        if (route.isSystemRoute() && !routerRecord.mHasModifyAudioRoutingPermission
                && !TextUtils.equals(route.getId(), defaultRouteId)) {
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifySessionCreationFailedToRouter,
                            routerRecord.mUserRecord.mHandler,
                            routerRecord, toOriginalRequestId(DUMMY_REQUEST_ID)));
        } else {
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::transferToRouteOnHandler,
                            routerRecord.mUserRecord.mHandler,
                            DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
        }
    }

    private void setSessionVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setSessionVolumeWithRouter2 | router: %d, session: %s, volume: %d",
                routerRecord.mRouterId,  uniqueSessionId, volume));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, uniqueSessionId, volume));
    }

    private void releaseSessionWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "releaseSessionWithRouter2 | router: %d, session: %s",
                routerRecord.mRouterId,  uniqueSessionId));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId));
    }

    ////////////////////////////////////////////////////////////
    ////  ***Locked methods related to MediaRouter2Manager
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////

    private List<RoutingSessionInfo> getRemoteSessionsLocked(
            @NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            Slog.w(TAG, "getRemoteSessionLocked: Ignoring unknown manager");
            return Collections.emptyList();
        }

        List<RoutingSessionInfo> sessionInfos = new ArrayList<>();
        for (MediaRoute2Provider provider : managerRecord.mUserRecord.mHandler.mRouteProviders) {
            if (!provider.mIsSystemRouteProvider) {
                sessionInfos.addAll(provider.getSessionInfos());
            }
        }
        return sessionInfos;
    }

    @GuardedBy("mLock")
    private void registerManagerLocked(@NonNull IMediaRouter2Manager manager,
            int uid, int pid, @NonNull String packageName, int userId) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            Slog.w(TAG, "registerManagerLocked: Same manager already exists. packageName="
                    + packageName);
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "registerManager | uid: %d, pid: %d, package: %s, user: %d",
                uid, pid, packageName, userId));

        mContext.enforcePermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid,
                "Must hold MEDIA_CONTENT_CONTROL permission.");

        UserRecord userRecord = getOrCreateUserRecordLocked(userId);
        managerRecord = new ManagerRecord(userRecord, manager, uid, pid, packageName);
        try {
            binder.linkToDeath(managerRecord, 0);
        } catch (RemoteException ex) {
            throw new RuntimeException("Media router manager died prematurely.", ex);
        }

        userRecord.mManagerRecords.add(managerRecord);
        mAllManagerRecords.put(binder, managerRecord);

        // Note: Features should be sent first before the routes. If not, the
        // RouteCallback#onRoutesAdded() for system MR2 will never be called with initial routes
        // due to the lack of features.
        for (RouterRecord routerRecord : userRecord.mRouterRecords) {
            // TODO: UserRecord <-> routerRecord, why do they reference each other?
            // How about removing mUserRecord from routerRecord?
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyDiscoveryPreferenceChangedToManager,
                        routerRecord.mUserRecord.mHandler, routerRecord, manager));
        }

        userRecord.mHandler.sendMessage(obtainMessage(UserHandler::notifyRoutesToManager,
                userRecord.mHandler, manager));
    }

    private void unregisterManagerLocked(@NonNull IMediaRouter2Manager manager, boolean died) {
        ManagerRecord managerRecord = mAllManagerRecords.remove(manager.asBinder());
        if (managerRecord == null) {
            return;
        }
        UserRecord userRecord = managerRecord.mUserRecord;

        Slog.i(TAG, TextUtils.formatSimple(
                "unregisterManager | package: %s, user: %d, manager: %d",
                managerRecord.mPackageName,
                userRecord.mUserId,
                managerRecord.mManagerId));

        userRecord.mManagerRecords.remove(managerRecord);
        managerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since manager removed from user
    }

    private void startScanLocked(@NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "startScan | manager: %d", managerRecord.mManagerId));

        managerRecord.startScan();
    }

    private void stopScanLocked(@NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "stopScan | manager: %d", managerRecord.mManagerId));

        managerRecord.stopScan();
    }

    private void setRouteVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setRouteVolumeWithManager | manager: %d, route: %s, volume: %d",
                managerRecord.mManagerId, route.getId(), volume));

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setRouteVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, route, volume));
    }

    private void requestCreateSessionWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull RoutingSessionInfo oldSession, @NonNull MediaRoute2Info route) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "requestCreateSessionWithManager | manager: %d, route: %s",
                managerRecord.mManagerId, route.getId()));

        String packageName = oldSession.getClientPackageName();

        RouterRecord routerRecord = managerRecord.mUserRecord.findRouterRecordLocked(packageName);
        if (routerRecord == null) {
            Slog.w(TAG, "requestCreateSessionWithManagerLocked: Ignoring session creation for "
                    + "unknown router.");
            try {
                managerRecord.mManager.notifyRequestFailed(requestId, REASON_UNKNOWN_ERROR);
            } catch (RemoteException ex) {
                Slog.w(TAG, "requestCreateSessionWithManagerLocked: Failed to notify failure. "
                        + "Manager probably died.");
            }
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        if (managerRecord.mLastSessionCreationRequest != null) {
            managerRecord.mUserRecord.mHandler.notifyRequestFailedToManager(
                    managerRecord.mManager,
                    toOriginalRequestId(managerRecord.mLastSessionCreationRequest
                            .mManagerRequestId),
                    REASON_UNKNOWN_ERROR);
            managerRecord.mLastSessionCreationRequest = null;
        }
        managerRecord.mLastSessionCreationRequest = new SessionCreationRequest(routerRecord,
                MediaRoute2ProviderService.REQUEST_ID_NONE, uniqueRequestId,
                oldSession, route);

        // Before requesting to the provider, get session hints from the media router.
        // As a return, media router will request to create a session.
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::requestRouterCreateSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, managerRecord, oldSession, route));
    }

    private void selectRouteWithManagerLocked(int requestId, @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "selectRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void deselectRouteWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "deselectRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void transferToRouteWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "transferToRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::transferToRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void setSessionVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setSessionVolumeWithManager | manager: %d, session: %s, volume: %d",
                managerRecord.mManagerId, uniqueSessionId, volume));

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, uniqueSessionId, volume));
    }

    private void releaseSessionWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "releaseSessionWithManager | manager: %d, session: %s",
                managerRecord.mManagerId, uniqueSessionId));

        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId));
    }

    ////////////////////////////////////////////////////////////
    ////  ***Locked methods used by both router2 and manager
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////

    @GuardedBy("mLock")
    private UserRecord getOrCreateUserRecordLocked(int userId) {
        UserRecord userRecord = mUserRecords.get(userId);
        if (userRecord == null) {
            userRecord = new UserRecord(userId);
            mUserRecords.put(userId, userRecord);
            userRecord.init();
            if (isUserActiveLocked(userId)) {
                userRecord.mHandler.sendMessage(
                        obtainMessage(UserHandler::start, userRecord.mHandler));
            }
        }
        return userRecord;
    }

    @GuardedBy("mLock")
    private void disposeUserIfNeededLocked(@NonNull UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (!isUserActiveLocked(userRecord.mUserId)
                && userRecord.mRouterRecords.isEmpty()
                && userRecord.mManagerRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::stop, userRecord.mHandler));
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    static long toUniqueRequestId(int requesterId, int originalRequestId) {
        return ((long) requesterId << 32) | originalRequestId;
    }

    static int toRequesterId(long uniqueRequestId) {
        return (int) (uniqueRequestId >> 32);
    }

    static int toOriginalRequestId(long uniqueRequestId) {
        return (int) uniqueRequestId;
    }

    final class UserRecord {
        public final int mUserId;
        //TODO: make records private for thread-safety
        final ArrayList<RouterRecord> mRouterRecords = new ArrayList<>();
        final ArrayList<ManagerRecord> mManagerRecords = new ArrayList<>();
        RouteDiscoveryPreference mCompositeDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }

        void init() {
            mHandler.init();
        }

        // TODO: This assumes that only one router exists in a package.
        //       Do this in Android S or later.
        RouterRecord findRouterRecordLocked(String packageName) {
            for (RouterRecord routerRecord : mRouterRecords) {
                if (TextUtils.equals(routerRecord.mPackageName, packageName)) {
                    return routerRecord;
                }
            }
            return null;
        }
    }

    final class RouterRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final String mPackageName;
        public final List<Integer> mSelectRouteSequenceNumbers;
        public final IMediaRouter2 mRouter;
        public final int mUid;
        public final int mPid;
        public final boolean mHasConfigureWifiDisplayPermission;
        public final boolean mHasModifyAudioRoutingPermission;
        public final int mRouterId;

        public RouteDiscoveryPreference mDiscoveryPreference;
        public MediaRoute2Info mSelectedRoute;

        RouterRecord(UserRecord userRecord, IMediaRouter2 router, int uid, int pid,
                String packageName, boolean hasConfigureWifiDisplayPermission,
                boolean hasModifyAudioRoutingPermission) {
            mUserRecord = userRecord;
            mPackageName = packageName;
            mSelectRouteSequenceNumbers = new ArrayList<>();
            mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
            mRouter = router;
            mUid = uid;
            mPid = pid;
            mHasConfigureWifiDisplayPermission = hasConfigureWifiDisplayPermission;
            mHasModifyAudioRoutingPermission = hasModifyAudioRoutingPermission;
            mRouterId = mNextRouterOrManagerId.getAndIncrement();
        }

        public void dispose() {
            mRouter.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            routerDied(this);
        }
    }

    final class ManagerRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouter2Manager mManager;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final int mManagerId;
        public SessionCreationRequest mLastSessionCreationRequest;
        public boolean mIsScanning;

        ManagerRecord(UserRecord userRecord, IMediaRouter2Manager manager,
                int uid, int pid, String packageName) {
            mUserRecord = userRecord;
            mManager = manager;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mManagerId = mNextRouterOrManagerId.getAndIncrement();
        }

        public void dispose() {
            mManager.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            managerDied(this);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
        }

        public void startScan() {
            if (mIsScanning) {
                return;
            }
            mIsScanning = true;
            mUserRecord.mHandler.sendMessage(PooledLambda.obtainMessage(
                    UserHandler::updateDiscoveryPreferenceOnHandler, mUserRecord.mHandler));
        }

        public void stopScan() {
            if (!mIsScanning) {
                return;
            }
            mIsScanning = false;
            mUserRecord.mHandler.sendMessage(PooledLambda.obtainMessage(
                    UserHandler::updateDiscoveryPreferenceOnHandler, mUserRecord.mHandler));
        }

        @Override
        public String toString() {
            return "Manager " + mPackageName + " (pid " + mPid + ")";
        }
    }

    static final class UserHandler extends Handler implements
            MediaRoute2ProviderWatcher.Callback,
            MediaRoute2Provider.Callback {

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        private final SystemMediaRoute2Provider mSystemProvider;
        private final ArrayList<MediaRoute2Provider> mRouteProviders =
                new ArrayList<>();

        private final List<MediaRoute2ProviderInfo> mLastProviderInfos = new ArrayList<>();
        private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
                new CopyOnWriteArrayList<>();
        private final Map<String, RouterRecord> mSessionToRouterMap = new ArrayMap<>();

        private boolean mRunning;

        // TODO: (In Android S+) Pull out SystemMediaRoute2Provider out of UserHandler.
        UserHandler(@NonNull MediaRouter2ServiceImpl service, @NonNull UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mSystemProvider = new SystemMediaRoute2Provider(service.mContext,
                    UserHandle.of(userRecord.mUserId));
            mRouteProviders.add(mSystemProvider);
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        void init() {
            mSystemProvider.setCallback(this);
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mSystemProvider.start();
                mWatcher.start();
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                mWatcher.stop(); // also stops all providers
                mSystemProvider.stop();
            }
        }

        @Override
        public void onAddProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy) {
            proxy.setCallback(this);
            mRouteProviders.add(proxy);
            proxy.updateDiscoveryPreference(mUserRecord.mCompositeDiscoveryPreference);
        }

        @Override
        public void onRemoveProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy) {
            mRouteProviders.remove(proxy);
        }

        @Override
        public void onProviderStateChanged(@NonNull MediaRoute2Provider provider) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onProviderStateChangedOnHandler,
                    this, provider));
        }

        @Override
        public void onSessionCreated(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionCreatedOnHandler,
                    this, provider, uniqueRequestId, sessionInfo));
        }

        @Override
        public void onSessionUpdated(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionInfoChangedOnHandler,
                    this, provider, sessionInfo));
        }

        @Override
        public void onSessionReleased(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionReleasedOnHandler,
                    this, provider, sessionInfo));
        }

        @Override
        public void onRequestFailed(@NonNull MediaRoute2Provider provider, long uniqueRequestId,
                int reason) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onRequestFailedOnHandler,
                    this, provider, uniqueRequestId, reason));
        }

        @Nullable
        public RouterRecord findRouterWithSessionLocked(@NonNull String uniqueSessionId) {
            return mSessionToRouterMap.get(uniqueSessionId);
        }

        @Nullable
        public ManagerRecord findManagerWithId(int managerId) {
            for (ManagerRecord manager : getManagerRecords()) {
                if (manager.mManagerId == managerId) {
                    return manager;
                }
            }
            return null;
        }

        public void maybeUpdateDiscoveryPreferenceForUid(int uid) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            boolean isUidRelevant;
            synchronized (service.mLock) {
                isUidRelevant = mUserRecord.mRouterRecords.stream().anyMatch(
                        router -> router.mUid == uid)
                        | mUserRecord.mManagerRecords.stream().anyMatch(
                            manager -> manager.mUid == uid);
            }
            if (isUidRelevant) {
                sendMessage(PooledLambda.obtainMessage(
                        UserHandler::updateDiscoveryPreferenceOnHandler, this));
            }
        }

        private void onProviderStateChangedOnHandler(@NonNull MediaRoute2Provider provider) {
            int providerInfoIndex = getLastProviderInfoIndex(provider.getUniqueId());
            MediaRoute2ProviderInfo currentInfo = provider.getProviderInfo();
            MediaRoute2ProviderInfo prevInfo =
                    (providerInfoIndex < 0) ? null : mLastProviderInfos.get(providerInfoIndex);
            if (Objects.equals(prevInfo, currentInfo)) return;

            List<MediaRoute2Info> addedRoutes = new ArrayList<>();
            List<MediaRoute2Info> removedRoutes = new ArrayList<>();
            List<MediaRoute2Info> changedRoutes = new ArrayList<>();
            if (prevInfo == null) {
                mLastProviderInfos.add(currentInfo);
                addedRoutes.addAll(currentInfo.getRoutes());
            } else if (currentInfo == null) {
                mLastProviderInfos.remove(prevInfo);
                removedRoutes.addAll(prevInfo.getRoutes());
            } else {
                mLastProviderInfos.set(providerInfoIndex, currentInfo);
                final Collection<MediaRoute2Info> prevRoutes = prevInfo.getRoutes();
                final Collection<MediaRoute2Info> currentRoutes = currentInfo.getRoutes();

                for (MediaRoute2Info route : currentRoutes) {
                    if (!route.isValid()) {
                        Slog.w(TAG, "onProviderStateChangedOnHandler: Ignoring invalid route : "
                                + route);
                        continue;
                    }
                    MediaRoute2Info prevRoute = prevInfo.getRoute(route.getOriginalId());
                    if (prevRoute == null) {
                        addedRoutes.add(route);
                    } else if (!Objects.equals(prevRoute, route)) {
                        changedRoutes.add(route);
                    }
                }

                for (MediaRoute2Info prevRoute : prevInfo.getRoutes()) {
                    if (currentInfo.getRoute(prevRoute.getOriginalId()) == null) {
                        removedRoutes.add(prevRoute);
                    }
                }
            }

            List<IMediaRouter2> routersWithModifyAudioRoutingPermission = getRouters(true);
            List<IMediaRouter2> routersWithoutModifyAudioRoutingPermission = getRouters(false);

            if (!addedRoutes.isEmpty()) {
                // If routes were added, currentInfo cannot be null.
                Slog.i(TAG,
                        toLoggingMessage(
                                /* source= */ "addProviderRoutes",
                                currentInfo.getUniqueId(),
                                (ArrayList) addedRoutes));
            }
            if (!removedRoutes.isEmpty()) {
                // If routes were removed, prevInfo cannot be null.
                Slog.i(TAG,
                        toLoggingMessage(
                                /* source= */ "removeProviderRoutes",
                                prevInfo.getUniqueId(),
                                (ArrayList) removedRoutes));
            }

            List<IMediaRouter2Manager> managers = getManagers();
            List<MediaRoute2Info> defaultRoute = new ArrayList<>();
            defaultRoute.add(mSystemProvider.getDefaultRoute());

            if (addedRoutes.size() > 0) {
                notifyRoutesAddedToRouters(routersWithModifyAudioRoutingPermission, addedRoutes);
                if (!provider.mIsSystemRouteProvider) {
                    notifyRoutesAddedToRouters(routersWithoutModifyAudioRoutingPermission,
                            addedRoutes);
                } else if (prevInfo == null) {
                    notifyRoutesAddedToRouters(routersWithoutModifyAudioRoutingPermission,
                            defaultRoute);
                } // 'else' is handled as changed routes
                notifyRoutesAddedToManagers(managers, addedRoutes);
            }
            if (removedRoutes.size() > 0) {
                notifyRoutesRemovedToRouters(routersWithModifyAudioRoutingPermission,
                        removedRoutes);
                if (!provider.mIsSystemRouteProvider) {
                    notifyRoutesRemovedToRouters(routersWithoutModifyAudioRoutingPermission,
                            removedRoutes);
                }
                notifyRoutesRemovedToManagers(managers, removedRoutes);
            }
            if (changedRoutes.size() > 0) {
                notifyRoutesChangedToRouters(routersWithModifyAudioRoutingPermission,
                        changedRoutes);
                if (!provider.mIsSystemRouteProvider) {
                    notifyRoutesChangedToRouters(routersWithoutModifyAudioRoutingPermission,
                            changedRoutes);
                } else if (prevInfo != null) {
                    notifyRoutesChangedToRouters(routersWithoutModifyAudioRoutingPermission,
                            defaultRoute);
                } // 'else' is handled as added routes
                notifyRoutesChangedToManagers(managers, changedRoutes);
            }
        }

        private static String toLoggingMessage(
                String source, String providerId, ArrayList<MediaRoute2Info> routes) {
            String routesString =
                    routes.stream()
                            .map(it -> String.format("%s | %s", it.getOriginalId(), it.getName()))
                            .collect(Collectors.joining(/* delimiter= */ ", "));
            return TextUtils.formatSimple("%s | provider: %s, routes: [%s]",
                    source, providerId, routesString);
        }

        private int getLastProviderInfoIndex(@NonNull String providerId) {
            for (int i = 0; i < mLastProviderInfos.size(); i++) {
                MediaRoute2ProviderInfo providerInfo = mLastProviderInfos.get(i);
                if (TextUtils.equals(providerInfo.getUniqueId(), providerId)) {
                    return i;
                }
            }
            return -1;
        }

        private void requestRouterCreateSessionOnHandler(long uniqueRequestId,
                @NonNull RouterRecord routerRecord, @NonNull ManagerRecord managerRecord,
                @NonNull RoutingSessionInfo oldSession, @NonNull MediaRoute2Info route) {
            try {
                if (route.isSystemRoute() && !routerRecord.mHasModifyAudioRoutingPermission) {
                    routerRecord.mRouter.requestCreateSessionByManager(uniqueRequestId,
                            oldSession, mSystemProvider.getDefaultRoute());
                } else {
                    routerRecord.mRouter.requestCreateSessionByManager(uniqueRequestId,
                            oldSession, route);
                }
            } catch (RemoteException ex) {
                Slog.w(TAG, "getSessionHintsForCreatingSessionOnHandler: "
                        + "Failed to request. Router probably died.", ex);
                notifyRequestFailedToManager(managerRecord.mManager,
                        toOriginalRequestId(uniqueRequestId), REASON_UNKNOWN_ERROR);
            }
        }

        private void requestCreateSessionWithRouter2OnHandler(long uniqueRequestId,
                long managerRequestId, @NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route, @Nullable Bundle sessionHints) {

            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "requestCreateSessionWithRouter2OnHandler: Ignoring session "
                        + "creation request since no provider found for given route=" + route);
                notifySessionCreationFailedToRouter(routerRecord,
                        toOriginalRequestId(uniqueRequestId));
                return;
            }

            SessionCreationRequest request =
                    new SessionCreationRequest(routerRecord, uniqueRequestId,
                            managerRequestId, oldSession, route);
            mSessionCreationRequests.add(request);

            provider.requestCreateSession(uniqueRequestId, routerRecord.mPackageName,
                    route.getOriginalId(), sessionHints);
        }

        // routerRecord can be null if the session is system's or RCN.
        private void selectRouteOnHandler(long uniqueRequestId, @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "selecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }
            provider.selectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's or RCN.
        private void deselectRouteOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "deselecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }

            provider.deselectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's or RCN.
        private void transferToRouteOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "transferring to")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }
            provider.transferToRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord is null if and only if the session is created without the request, which
        // includes the system's session and RCN cases.
        private boolean checkArgumentsForSessionControl(@Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route,
                @NonNull String description) {
            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                Slog.w(TAG, "Ignoring " + description + " route since no provider found for "
                        + "given route=" + route);
                return false;
            }

            // Bypass checking router if it's the system session (routerRecord should be null)
            if (TextUtils.equals(getProviderId(uniqueSessionId), mSystemProvider.getUniqueId())) {
                return true;
            }

            RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(TAG, "Ignoring " + description + " route from non-matching router. "
                        + "packageName=" + routerRecord.mPackageName + " route=" + route);
                return false;
            }

            final String sessionId = getOriginalId(uniqueSessionId);
            if (sessionId == null) {
                Slog.w(TAG, "Failed to get original session id from unique session id. "
                        + "uniqueSessionId=" + uniqueSessionId);
                return false;
            }

            return true;
        }

        private void setRouteVolumeOnHandler(long uniqueRequestId, @NonNull MediaRoute2Info route,
                int volume) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "setRouteVolumeOnHandler: Couldn't find provider for route=" + route);
                return;
            }
            provider.setRouteVolume(uniqueRequestId, route.getOriginalId(), volume);
        }

        private void setSessionVolumeOnHandler(long uniqueRequestId,
                @NonNull String uniqueSessionId, int volume) {
            final MediaRoute2Provider provider = findProvider(getProviderId(uniqueSessionId));
            if (provider == null) {
                Slog.w(TAG, "setSessionVolumeOnHandler: Couldn't find provider for session id="
                        + uniqueSessionId);
                return;
            }
            provider.setSessionVolume(uniqueRequestId, getOriginalId(uniqueSessionId), volume);
        }

        private void releaseSessionOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord, @NonNull String uniqueSessionId) {
            final RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(TAG, "Ignoring releasing session from non-matching router. packageName="
                        + (routerRecord == null ? null : routerRecord.mPackageName)
                        + " uniqueSessionId=" + uniqueSessionId);
                return;
            }

            final String providerId = getProviderId(uniqueSessionId);
            if (providerId == null) {
                Slog.w(TAG, "Ignoring releasing session with invalid unique session ID. "
                        + "uniqueSessionId=" + uniqueSessionId);
                return;
            }

            final String sessionId = getOriginalId(uniqueSessionId);
            if (sessionId == null) {
                Slog.w(TAG, "Ignoring releasing session with invalid unique session ID. "
                        + "uniqueSessionId=" + uniqueSessionId + " providerId=" + providerId);
                return;
            }

            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                Slog.w(TAG, "Ignoring releasing session since no provider found for given "
                        + "providerId=" + providerId);
                return;
            }

            provider.releaseSession(uniqueRequestId, sessionId);
        }

        private void onSessionCreatedOnHandler(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, @NonNull RoutingSessionInfo sessionInfo) {
            SessionCreationRequest matchingRequest = null;

            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mUniqueRequestId == uniqueRequestId
                        && TextUtils.equals(
                        request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            long managerRequestId = (matchingRequest == null)
                    ? MediaRoute2ProviderService.REQUEST_ID_NONE
                    : matchingRequest.mManagerRequestId;
            notifySessionCreatedToManagers(managerRequestId, sessionInfo);

            if (matchingRequest == null) {
                Slog.w(TAG, "Ignoring session creation result for unknown request. "
                        + "uniqueRequestId=" + uniqueRequestId + ", sessionInfo=" + sessionInfo);
                return;
            }

            mSessionCreationRequests.remove(matchingRequest);
            // Not to show old session
            MediaRoute2Provider oldProvider =
                    findProvider(matchingRequest.mOldSession.getProviderId());
            if (oldProvider != null) {
                oldProvider.prepareReleaseSession(matchingRequest.mOldSession.getId());
            } else {
                Slog.w(TAG, "onSessionCreatedOnHandler: Can't find provider for an old session. "
                        + "session=" + matchingRequest.mOldSession);
            }

            // Succeeded
            if (sessionInfo.isSystemSession()
                    && !matchingRequest.mRouterRecord.mHasModifyAudioRoutingPermission) {
                notifySessionCreatedToRouter(matchingRequest.mRouterRecord,
                        toOriginalRequestId(uniqueRequestId),
                        mSystemProvider.getDefaultSessionInfo());
            } else {
                notifySessionCreatedToRouter(matchingRequest.mRouterRecord,
                        toOriginalRequestId(uniqueRequestId), sessionInfo);
            }
            mSessionToRouterMap.put(sessionInfo.getId(), matchingRequest.mRouterRecord);
        }

        private void onSessionInfoChangedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionUpdatedToManagers(managers, sessionInfo);

            // For system provider, notify all routers.
            if (provider == mSystemProvider) {
                MediaRouter2ServiceImpl service = mServiceRef.get();
                if (service == null) {
                    return;
                }
                notifySessionInfoChangedToRouters(getRouters(true), sessionInfo);
                notifySessionInfoChangedToRouters(getRouters(false),
                        mSystemProvider.getDefaultSessionInfo());
                return;
            }

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "onSessionInfoChangedOnHandler: No matching router found for session="
                        + sessionInfo);
                return;
            }
            notifySessionInfoChangedToRouter(routerRecord, sessionInfo);
        }

        private void onSessionReleasedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionReleasedToManagers(managers, sessionInfo);

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "onSessionReleasedOnHandler: No matching router found for session="
                        + sessionInfo);
                return;
            }
            notifySessionReleasedToRouter(routerRecord, sessionInfo);
        }

        private void onRequestFailedOnHandler(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, int reason) {
            if (handleSessionCreationRequestFailed(provider, uniqueRequestId, reason)) {
                return;
            }

            final int requesterId = toRequesterId(uniqueRequestId);
            ManagerRecord manager = findManagerWithId(requesterId);
            if (manager != null) {
                notifyRequestFailedToManager(
                        manager.mManager, toOriginalRequestId(uniqueRequestId), reason);
                return;
            }

            // Currently, only the manager can get notified of failures.
            // TODO: Notify router too when the related callback is introduced.
        }

        private boolean handleSessionCreationRequestFailed(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, int reason) {
            // Check whether the failure is about creating a session
            SessionCreationRequest matchingRequest = null;
            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mUniqueRequestId == uniqueRequestId && TextUtils.equals(
                        request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest == null) {
                // The failure is not about creating a session.
                return false;
            }

            mSessionCreationRequests.remove(matchingRequest);

            // Notify the requester about the failure.
            // The call should be made by either MediaRouter2 or MediaRouter2Manager.
            if (matchingRequest.mManagerRequestId == MediaRouter2Manager.REQUEST_ID_NONE) {
                notifySessionCreationFailedToRouter(
                        matchingRequest.mRouterRecord, toOriginalRequestId(uniqueRequestId));
            } else {
                final int requesterId = toRequesterId(matchingRequest.mManagerRequestId);
                ManagerRecord manager = findManagerWithId(requesterId);
                if (manager != null) {
                    notifyRequestFailedToManager(manager.mManager,
                            toOriginalRequestId(matchingRequest.mManagerRequestId), reason);
                }
            }
            return true;
        }

        private void notifySessionCreatedToRouter(@NonNull RouterRecord routerRecord,
                int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionCreated(requestId, sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session creation."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionCreationFailedToRouter(@NonNull RouterRecord routerRecord,
                int requestId) {
            try {
                routerRecord.mRouter.notifySessionCreated(requestId,
                        /* sessionInfo= */ null);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session creation failure."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionInfoChangedToRouter(@NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionInfoChanged(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session info change."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionReleasedToRouter(@NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionReleased(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session release."
                        + " Router probably died.", ex);
            }
        }

        private List<IMediaRouter2> getAllRouters() {
            final List<IMediaRouter2> routers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return routers;
            }
            synchronized (service.mLock) {
                for (RouterRecord routerRecord : mUserRecord.mRouterRecords) {
                    routers.add(routerRecord.mRouter);
                }
            }
            return routers;
        }

        private List<IMediaRouter2> getRouters(boolean hasModifyAudioRoutingPermission) {
            final List<IMediaRouter2> routers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return routers;
            }
            synchronized (service.mLock) {
                for (RouterRecord routerRecord : mUserRecord.mRouterRecords) {
                    if (hasModifyAudioRoutingPermission
                            == routerRecord.mHasModifyAudioRoutingPermission) {
                        routers.add(routerRecord.mRouter);
                    }
                }
            }
            return routers;
        }

        private List<IMediaRouter2Manager> getManagers() {
            final List<IMediaRouter2Manager> managers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return managers;
            }
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            return managers;
        }

        private List<RouterRecord> getRouterRecords() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return Collections.emptyList();
            }
            synchronized (service.mLock) {
                return new ArrayList<>(mUserRecord.mRouterRecords);
            }
        }

        private List<ManagerRecord> getManagerRecords() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return Collections.emptyList();
            }
            synchronized (service.mLock) {
                return new ArrayList<>(mUserRecord.mManagerRecords);
            }
        }

        private void notifyRouterRegistered(@NonNull RouterRecord routerRecord) {
            List<MediaRoute2Info> currentRoutes = new ArrayList<>();

            MediaRoute2ProviderInfo systemProviderInfo = null;
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                // TODO: Create MediaRoute2ProviderInfo#isSystemProvider()
                if (TextUtils.equals(providerInfo.getUniqueId(), mSystemProvider.getUniqueId())) {
                    // Adding routes from system provider will be handled below, so skip it here.
                    systemProviderInfo = providerInfo;
                    continue;
                }
                currentRoutes.addAll(providerInfo.getRoutes());
            }

            RoutingSessionInfo currentSystemSessionInfo;
            if (routerRecord.mHasModifyAudioRoutingPermission) {
                if (systemProviderInfo != null) {
                    currentRoutes.addAll(systemProviderInfo.getRoutes());
                } else {
                    // This shouldn't happen.
                    Slog.wtf(TAG, "System route provider not found.");
                }
                currentSystemSessionInfo = mSystemProvider.getSessionInfos().get(0);
            } else {
                currentRoutes.add(mSystemProvider.getDefaultRoute());
                currentSystemSessionInfo = mSystemProvider.getDefaultSessionInfo();
            }

            if (currentRoutes.size() == 0) {
                return;
            }

            try {
                routerRecord.mRouter.notifyRouterRegistered(
                        currentRoutes, currentSystemSessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router registered. Router probably died.", ex);
            }
        }

        private void notifyRoutesAddedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Router probably died.", ex);
                }
            }
        }

        private void notifySessionInfoChangedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifySessionInfoChanged(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify session info changed. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesToManager(@NonNull IMediaRouter2Manager manager) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                manager.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Manager probably died.", ex);
            }
        }

        private void notifyRoutesAddedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionCreatedToManagers(long managerRequestId,
                @NonNull RoutingSessionInfo session) {
            int requesterId = toRequesterId(managerRequestId);
            int originalRequestId = toOriginalRequestId(managerRequestId);

            for (ManagerRecord manager : getManagerRecords()) {
                try {
                    manager.mManager.notifySessionCreated(
                            ((manager.mManagerId == requesterId) ? originalRequestId :
                                    MediaRouter2Manager.REQUEST_ID_NONE), session);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionCreatedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionUpdatedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionUpdated(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionUpdatedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionReleasedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionReleased(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionReleasedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifyDiscoveryPreferenceChangedToManager(@NonNull RouterRecord routerRecord,
                @NonNull IMediaRouter2Manager manager) {
            try {
                manager.notifyDiscoveryPreferenceChanged(routerRecord.mPackageName,
                        routerRecord.mDiscoveryPreference);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify preferred features changed."
                        + " Manager probably died.", ex);
            }
        }

        private void notifyDiscoveryPreferenceChangedToManagers(@NonNull String routerPackageName,
                @Nullable RouteDiscoveryPreference discoveryPreference) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<IMediaRouter2Manager> managers = new ArrayList<>();
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyDiscoveryPreferenceChanged(routerPackageName,
                            discoveryPreference);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify preferred features changed."
                            + " Manager probably died.", ex);
                }
            }
        }

        private void notifyRequestFailedToManager(@NonNull IMediaRouter2Manager manager,
                int requestId, int reason) {
            try {
                manager.notifyRequestFailed(requestId, reason);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify manager of the request failure."
                        + " Manager probably died.", ex);
            }
        }

        private void updateDiscoveryPreferenceOnHandler() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<RouteDiscoveryPreference> discoveryPreferences = Collections.emptyList();
            List<RouterRecord> routerRecords = getRouterRecords();
            List<ManagerRecord> managerRecords = getManagerRecords();

            boolean shouldBindProviders = false;

            if (service.mPowerManager.isInteractive()) {
                boolean isManagerScanning = managerRecords.stream().anyMatch(manager ->
                        manager.mIsScanning && service.mActivityManager
                                .getPackageImportance(manager.mPackageName)
                                <= PACKAGE_IMPORTANCE_FOR_DISCOVERY);

                if (isManagerScanning) {
                    discoveryPreferences = routerRecords.stream()
                            .map(record -> record.mDiscoveryPreference)
                            .collect(Collectors.toList());
                    shouldBindProviders = true;
                } else {
                    discoveryPreferences = routerRecords.stream().filter(record ->
                            service.mActivityManager.getPackageImportance(record.mPackageName)
                                    <= PACKAGE_IMPORTANCE_FOR_DISCOVERY)
                            .map(record -> record.mDiscoveryPreference)
                            .collect(Collectors.toList());
                }
            }

            for (MediaRoute2Provider provider : mRouteProviders) {
                if (provider instanceof MediaRoute2ProviderServiceProxy) {
                    ((MediaRoute2ProviderServiceProxy) provider)
                            .setManagerScanning(shouldBindProviders);
                }
            }

            // Build a composite RouteDiscoveryPreference that matches all of the routes
            // that match one or more of the individual discovery preferences. It may also
            // match additional routes. The composite RouteDiscoveryPreference can be used
            // to query route providers once to obtain all of the routes of interest, which
            // can be subsequently filtered for the individual discovery preferences.
            Set<String> preferredFeatures = new HashSet<>();
            boolean activeScan = false;
            for (RouteDiscoveryPreference preference : discoveryPreferences) {
                preferredFeatures.addAll(preference.getPreferredFeatures());
                activeScan |= preference.shouldPerformActiveScan();
            }
            RouteDiscoveryPreference newPreference = new RouteDiscoveryPreference.Builder(
                    List.copyOf(preferredFeatures), activeScan).build();

            synchronized (service.mLock) {
                if (newPreference.equals(mUserRecord.mCompositeDiscoveryPreference)) {
                    return;
                }
                mUserRecord.mCompositeDiscoveryPreference = newPreference;
            }
            for (MediaRoute2Provider provider : mRouteProviders) {
                provider.updateDiscoveryPreference(mUserRecord.mCompositeDiscoveryPreference);
            }
        }

        private MediaRoute2Provider findProvider(@Nullable String providerId) {
            for (MediaRoute2Provider provider : mRouteProviders) {
                if (TextUtils.equals(provider.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }

    }
    static final class SessionCreationRequest {
        public final RouterRecord mRouterRecord;
        public final long mUniqueRequestId;
        public final long mManagerRequestId;
        public final RoutingSessionInfo mOldSession;
        public final MediaRoute2Info mRoute;

        SessionCreationRequest(@NonNull RouterRecord routerRecord, long uniqueRequestId,
                long managerRequestId, @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route) {
            mRouterRecord = routerRecord;
            mUniqueRequestId = uniqueRequestId;
            mManagerRequestId = managerRequestId;
            mOldSession = oldSession;
            mRoute = route;
        }
    }
}
