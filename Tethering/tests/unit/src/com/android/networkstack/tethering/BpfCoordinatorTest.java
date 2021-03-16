/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStats.UID_TETHERING;
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;
import static android.system.OsConstants.ETH_P_IPV6;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;
import static com.android.networkstack.tethering.BpfCoordinator.StatsType;
import static com.android.networkstack.tethering.BpfCoordinator.StatsType.STATS_PER_IFACE;
import static com.android.networkstack.tethering.BpfCoordinator.StatsType.STATS_PER_UID;
import static com.android.networkstack.tethering.BpfUtils.DOWNSTREAM;
import static com.android.networkstack.tethering.BpfUtils.UPSTREAM;
import static com.android.networkstack.tethering.TetheringConfiguration.DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.NetworkStats;
import android.net.TetherOffloadRuleParcel;
import android.net.TetherStatsParcel;
import android.net.ip.ConntrackMonitor;
import android.net.ip.ConntrackMonitor.ConntrackEventConsumer;
import android.net.ip.IpServer;
import android.net.util.SharedLog;
import android.os.Build;
import android.os.Handler;
import android.os.test.TestLooper;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.net.module.util.NetworkStackConstants;
import com.android.net.module.util.Struct;
import com.android.networkstack.tethering.BpfCoordinator.Ipv6ForwardingRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.TestableNetworkStatsProviderCbBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BpfCoordinatorTest {
    private static final int DOWNSTREAM_IFINDEX = 10;
    private static final MacAddress DOWNSTREAM_MAC = MacAddress.ALL_ZEROS_ADDRESS;
    private static final InetAddress NEIGH_A = InetAddresses.parseNumericAddress("2001:db8::1");
    private static final InetAddress NEIGH_B = InetAddresses.parseNumericAddress("2001:db8::2");
    private static final MacAddress MAC_A = MacAddress.fromString("00:00:00:00:00:0a");
    private static final MacAddress MAC_B = MacAddress.fromString("11:22:33:00:00:0b");

    // The test fake BPF map class is needed because the test has no privilege to access the BPF
    // map. All member functions which eventually call JNI to access the real native BPF map need
    // to be overridden.
    // TODO: consider moving to an individual file.
    private class TestBpfMap<K extends Struct, V extends Struct> extends BpfMap<K, V> {
        private final HashMap<K, V> mMap = new HashMap<K, V>();

        TestBpfMap(final Class<K> key, final Class<V> value) {
            super(key, value);
        }

        @Override
        public void forEach(BiConsumer<K, V> action) throws ErrnoException {
            // TODO: consider using mocked #getFirstKey and #getNextKey to iterate. It helps to
            // implement the entry deletion in the iteration if required.
            for (Map.Entry<K, V> entry : mMap.entrySet()) {
                action.accept(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void updateEntry(K key, V value) throws ErrnoException {
            mMap.put(key, value);
        }

        @Override
        public void insertEntry(K key, V value) throws ErrnoException,
                IllegalArgumentException {
            // The entry is created if and only if it doesn't exist. See BpfMap#insertEntry.
            if (mMap.get(key) != null) {
                throw new IllegalArgumentException(key + " already exist");
            }
            mMap.put(key, value);
        }

        @Override
        public boolean deleteEntry(Struct key) throws ErrnoException {
            return mMap.remove(key) != null;
        }

        @Override
        public V getValue(@NonNull K key) throws ErrnoException {
            // Return value for a given key. Otherwise, return null without an error ENOENT.
            // BpfMap#getValue treats that the entry is not found as no error.
            return mMap.get(key);
        }

        @Override
        public void clear() throws ErrnoException {
            // TODO: consider using mocked #getFirstKey and #deleteEntry to implement.
            mMap.clear();
        }
    };

    @Mock private NetworkStatsManager mStatsManager;
    @Mock private INetd mNetd;
    @Mock private IpServer mIpServer;
    @Mock private IpServer mIpServer2;
    @Mock private TetheringConfiguration mTetherConfig;
    @Mock private ConntrackMonitor mConntrackMonitor;
    @Mock private BpfMap<Tether4Key, Tether4Value> mBpfDownstream4Map;
    @Mock private BpfMap<Tether4Key, Tether4Value> mBpfUpstream4Map;
    @Mock private BpfMap<TetherDownstream6Key, Tether6Value> mBpfDownstream6Map;
    @Mock private BpfMap<TetherUpstream6Key, Tether6Value> mBpfUpstream6Map;

    // Late init since methods must be called by the thread that created this object.
    private TestableNetworkStatsProviderCbBinder mTetherStatsProviderCb;
    private BpfCoordinator.BpfTetherStatsProvider mTetherStatsProvider;
    private final ArgumentCaptor<ArrayList> mStringArrayCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    private final TestLooper mTestLooper = new TestLooper();
    private final TestBpfMap<TetherStatsKey, TetherStatsValue> mBpfStatsMap =
            spy(new TestBpfMap<>(TetherStatsKey.class, TetherStatsValue.class));
    private final TestBpfMap<TetherLimitKey, TetherLimitValue> mBpfLimitMap =
            spy(new TestBpfMap<>(TetherLimitKey.class, TetherLimitValue.class));
    private BpfCoordinator.Dependencies mDeps =
            spy(new BpfCoordinator.Dependencies() {
                    @NonNull
                    public Handler getHandler() {
                        return new Handler(mTestLooper.getLooper());
                    }

                    @NonNull
                    public INetd getNetd() {
                        return mNetd;
                    }

                    @NonNull
                    public NetworkStatsManager getNetworkStatsManager() {
                        return mStatsManager;
                    }

                    @NonNull
                    public SharedLog getSharedLog() {
                        return new SharedLog("test");
                    }

                    @Nullable
                    public TetheringConfiguration getTetherConfig() {
                        return mTetherConfig;
                    }

                    @NonNull
                    public ConntrackMonitor getConntrackMonitor(ConntrackEventConsumer consumer) {
                        return mConntrackMonitor;
                    }

                    @Nullable
                    public BpfMap<Tether4Key, Tether4Value> getBpfDownstream4Map() {
                        return mBpfDownstream4Map;
                    }

                    @Nullable
                    public BpfMap<Tether4Key, Tether4Value> getBpfUpstream4Map() {
                        return mBpfUpstream4Map;
                    }

                    @Nullable
                    public BpfMap<TetherDownstream6Key, Tether6Value> getBpfDownstream6Map() {
                        return mBpfDownstream6Map;
                    }

                    @Nullable
                    public BpfMap<TetherUpstream6Key, Tether6Value> getBpfUpstream6Map() {
                        return mBpfUpstream6Map;
                    }

                    @Nullable
                    public BpfMap<TetherStatsKey, TetherStatsValue> getBpfStatsMap() {
                        return mBpfStatsMap;
                    }

                    @Nullable
                    public BpfMap<TetherLimitKey, TetherLimitValue> getBpfLimitMap() {
                        return mBpfLimitMap;
                    }
            });

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(true /* default value */);
    }

    private void waitForIdle() {
        mTestLooper.dispatchAll();
    }

    private void setupFunctioningNetdInterface() throws Exception {
        when(mNetd.tetherOffloadGetStats()).thenReturn(new TetherStatsParcel[0]);
    }

    @NonNull
    private BpfCoordinator makeBpfCoordinator() throws Exception {
        final BpfCoordinator coordinator = new BpfCoordinator(mDeps);
        final ArgumentCaptor<BpfCoordinator.BpfTetherStatsProvider>
                tetherStatsProviderCaptor =
                ArgumentCaptor.forClass(BpfCoordinator.BpfTetherStatsProvider.class);
        verify(mStatsManager).registerNetworkStatsProvider(anyString(),
                tetherStatsProviderCaptor.capture());
        mTetherStatsProvider = tetherStatsProviderCaptor.getValue();
        assertNotNull(mTetherStatsProvider);
        mTetherStatsProviderCb = new TestableNetworkStatsProviderCbBinder();
        mTetherStatsProvider.setProviderCallbackBinder(mTetherStatsProviderCb);
        return coordinator;
    }

    @NonNull
    private static NetworkStats.Entry buildTestEntry(@NonNull StatsType how,
            @NonNull String iface, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        return new NetworkStats.Entry(iface, how == STATS_PER_IFACE ? UID_ALL : UID_TETHERING,
                SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes,
                rxPackets, txBytes, txPackets, 0L);
    }

    @NonNull
    private static TetherStatsParcel buildTestTetherStatsParcel(@NonNull Integer ifIndex,
            long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final TetherStatsParcel parcel = new TetherStatsParcel();
        parcel.ifIndex = ifIndex;
        parcel.rxBytes = rxBytes;
        parcel.rxPackets = rxPackets;
        parcel.txBytes = txBytes;
        parcel.txPackets = txPackets;
        return parcel;
    }

    // Update a stats entry or create if not exists.
    private void updateStatsEntryToStatsMap(@NonNull TetherStatsParcel stats) throws Exception {
        final TetherStatsKey key = new TetherStatsKey(stats.ifIndex);
        final TetherStatsValue value = new TetherStatsValue(stats.rxPackets, stats.rxBytes,
                0L /* rxErrors */, stats.txPackets, stats.txBytes, 0L /* txErrors */);
        mBpfStatsMap.updateEntry(key, value);
    }

    private void updateStatsEntry(@NonNull TetherStatsParcel stats) throws Exception {
        if (mDeps.isAtLeastS()) {
            updateStatsEntryToStatsMap(stats);
        } else {
            when(mNetd.tetherOffloadGetStats()).thenReturn(new TetherStatsParcel[] {stats});
        }
    }

    // Update specific tether stats list and wait for the stats cache is updated by polling thread
    // in the coordinator. Beware of that it is only used for the default polling interval.
    // Note that the mocked tetherOffloadGetStats of netd replaces all stats entries because it
    // doesn't store the previous entries.
    private void updateStatsEntriesAndWaitForUpdate(@NonNull TetherStatsParcel[] tetherStatsList)
            throws Exception {
        if (mDeps.isAtLeastS()) {
            for (TetherStatsParcel stats : tetherStatsList) {
                updateStatsEntry(stats);
            }
        } else {
            when(mNetd.tetherOffloadGetStats()).thenReturn(tetherStatsList);
        }

        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
    }

    // In tests, the stats need to be set before deleting the last rule.
    // The reason is that BpfCoordinator#tetherOffloadRuleRemove reads the stats
    // of the deleting interface after the last rule deleted. #tetherOffloadRuleRemove
    // does the interface cleanup failed if there is no stats for the deleting interface.
    // Note that the mocked tetherOffloadGetAndClearStats of netd replaces all stats entries
    // because it doesn't store the previous entries.
    private void updateStatsEntryForTetherOffloadGetAndClearStats(TetherStatsParcel stats)
            throws Exception {
        if (mDeps.isAtLeastS()) {
            updateStatsEntryToStatsMap(stats);
        } else {
            when(mNetd.tetherOffloadGetAndClearStats(stats.ifIndex)).thenReturn(stats);
        }
    }

    private void clearStatsInvocations() {
        if (mDeps.isAtLeastS()) {
            clearInvocations(mBpfStatsMap);
        } else {
            clearInvocations(mNetd);
        }
    }

    private <T> T verifyWithOrder(@Nullable InOrder inOrder, @NonNull T t) {
        if (inOrder != null) {
            return inOrder.verify(t);
        } else {
            return verify(t);
        }
    }

    private void verifyTetherOffloadGetStats() throws Exception {
        if (mDeps.isAtLeastS()) {
            verify(mBpfStatsMap).forEach(any());
        } else {
            verify(mNetd).tetherOffloadGetStats();
        }
    }

    private void verifyNeverTetherOffloadGetStats() throws Exception {
        if (mDeps.isAtLeastS()) {
            verify(mBpfStatsMap, never()).forEach(any());
        } else {
            verify(mNetd, never()).tetherOffloadGetStats();
        }
    }

    private void verifyStartUpstreamIpv6Forwarding(@Nullable InOrder inOrder, int downstreamIfIndex,
            int upstreamIfindex) throws Exception {
        if (!mDeps.isAtLeastS()) return;
        final TetherUpstream6Key key = new TetherUpstream6Key(downstreamIfIndex);
        final Tether6Value value = new Tether6Value(upstreamIfindex,
                MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS,
                ETH_P_IPV6, NetworkStackConstants.ETHER_MTU);
        verifyWithOrder(inOrder, mBpfUpstream6Map).insertEntry(key, value);
    }

    private void verifyStopUpstreamIpv6Forwarding(@Nullable InOrder inOrder, int downstreamIfIndex)
            throws Exception {
        if (!mDeps.isAtLeastS()) return;
        final TetherUpstream6Key key = new TetherUpstream6Key(downstreamIfIndex);
        verifyWithOrder(inOrder, mBpfUpstream6Map).deleteEntry(key);
    }

    private void verifyNoUpstreamIpv6ForwardingChange(@Nullable InOrder inOrder) throws Exception {
        if (!mDeps.isAtLeastS()) return;
        if (inOrder != null) {
            inOrder.verify(mBpfUpstream6Map, never()).deleteEntry(any());
            inOrder.verify(mBpfUpstream6Map, never()).insertEntry(any(), any());
            inOrder.verify(mBpfUpstream6Map, never()).updateEntry(any(), any());
        } else {
            verify(mBpfUpstream6Map, never()).deleteEntry(any());
            verify(mBpfUpstream6Map, never()).insertEntry(any(), any());
            verify(mBpfUpstream6Map, never()).updateEntry(any(), any());
        }
    }

    private void verifyTetherOffloadRuleAdd(@Nullable InOrder inOrder,
            @NonNull Ipv6ForwardingRule rule) throws Exception {
        if (mDeps.isAtLeastS()) {
            verifyWithOrder(inOrder, mBpfDownstream6Map).updateEntry(
                    rule.makeTetherDownstream6Key(), rule.makeTether6Value());
        } else {
            verifyWithOrder(inOrder, mNetd).tetherOffloadRuleAdd(matches(rule));
        }
    }

    private void verifyNeverTetherOffloadRuleAdd() throws Exception {
        if (mDeps.isAtLeastS()) {
            verify(mBpfDownstream6Map, never()).updateEntry(any(), any());
        } else {
            verify(mNetd, never()).tetherOffloadRuleAdd(any());
        }
    }

    private void verifyTetherOffloadRuleRemove(@Nullable InOrder inOrder,
            @NonNull final Ipv6ForwardingRule rule) throws Exception {
        if (mDeps.isAtLeastS()) {
            verifyWithOrder(inOrder, mBpfDownstream6Map).deleteEntry(
                    rule.makeTetherDownstream6Key());
        } else {
            verifyWithOrder(inOrder, mNetd).tetherOffloadRuleRemove(matches(rule));
        }
    }

    private void verifyNeverTetherOffloadRuleRemove() throws Exception {
        if (mDeps.isAtLeastS()) {
            verify(mBpfDownstream6Map, never()).deleteEntry(any());
        } else {
            verify(mNetd, never()).tetherOffloadRuleRemove(any());
        }
    }

    private void verifyTetherOffloadSetInterfaceQuota(@Nullable InOrder inOrder, int ifIndex,
            long quotaBytes, boolean isInit) throws Exception {
        if (mDeps.isAtLeastS()) {
            final TetherStatsKey key = new TetherStatsKey(ifIndex);
            verifyWithOrder(inOrder, mBpfStatsMap).getValue(key);
            if (isInit) {
                verifyWithOrder(inOrder, mBpfStatsMap).insertEntry(key, new TetherStatsValue(
                        0L /* rxPackets */, 0L /* rxBytes */, 0L /* rxErrors */,
                        0L /* txPackets */, 0L /* txBytes */, 0L /* txErrors */));
            }
            verifyWithOrder(inOrder, mBpfLimitMap).updateEntry(new TetherLimitKey(ifIndex),
                    new TetherLimitValue(quotaBytes));
        } else {
            verifyWithOrder(inOrder, mNetd).tetherOffloadSetInterfaceQuota(ifIndex, quotaBytes);
        }
    }

    private void verifyNeverTetherOffloadSetInterfaceQuota(@Nullable InOrder inOrder)
            throws Exception {
        if (mDeps.isAtLeastS()) {
            inOrder.verify(mBpfStatsMap, never()).getValue(any());
            inOrder.verify(mBpfStatsMap, never()).insertEntry(any(), any());
            inOrder.verify(mBpfLimitMap, never()).updateEntry(any(), any());
        } else {
            inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());
        }
    }

    private void verifyTetherOffloadGetAndClearStats(@Nullable InOrder inOrder, int ifIndex)
            throws Exception {
        if (mDeps.isAtLeastS()) {
            inOrder.verify(mBpfStatsMap).getValue(new TetherStatsKey(ifIndex));
            inOrder.verify(mBpfStatsMap).deleteEntry(new TetherStatsKey(ifIndex));
            inOrder.verify(mBpfLimitMap).deleteEntry(new TetherLimitKey(ifIndex));
        } else {
            inOrder.verify(mNetd).tetherOffloadGetAndClearStats(ifIndex);
        }
    }

    // S+ and R api minimum tests.
    // The following tests are used to provide minimum checking for the APIs on different flow.
    // The auto merge is not enabled on mainline prod. The code flow R may be verified at the
    // late stage by manual cherry pick. It is risky if the R code flow has broken and be found at
    // the last minute.
    // TODO: remove once presubmit tests on R even the code is submitted on S.
    private void checkTetherOffloadRuleAddAndRemove(boolean usingApiS) throws Exception {
        setupFunctioningNetdInterface();

        // Replace Dependencies#isAtLeastS() for testing R and S+ BPF map apis. Note that |mDeps|
        // must be mocked before calling #makeBpfCoordinator which use |mDeps| to initialize the
        // coordinator.
        doReturn(usingApiS).when(mDeps).isAtLeastS();
        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // InOrder is required because mBpfStatsMap may be accessed by both
        // BpfCoordinator#tetherOffloadRuleAdd and BpfCoordinator#tetherOffloadGetAndClearStats.
        // The #verifyTetherOffloadGetAndClearStats can't distinguish who has ever called
        // mBpfStatsMap#getValue and get a wrong calling count which counts all.
        final InOrder inOrder = inOrder(mNetd, mBpfDownstream6Map, mBpfLimitMap, mBpfStatsMap);
        final Ipv6ForwardingRule rule = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);
        coordinator.tetherOffloadRuleAdd(mIpServer, rule);
        verifyTetherOffloadRuleAdd(inOrder, rule);
        verifyTetherOffloadSetInterfaceQuota(inOrder, mobileIfIndex, QUOTA_UNLIMITED,
                true /* isInit */);

        // Removing the last rule on current upstream immediately sends the cleanup stuff to netd.
        updateStatsEntryForTetherOffloadGetAndClearStats(
                buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0));
        coordinator.tetherOffloadRuleRemove(mIpServer, rule);
        verifyTetherOffloadRuleRemove(inOrder, rule);
        verifyTetherOffloadGetAndClearStats(inOrder, mobileIfIndex);
    }

    // TODO: remove once presubmit tests on R even the code is submitted on S.
    @Test
    public void testTetherOffloadRuleAddAndRemoveSdkR() throws Exception {
        checkTetherOffloadRuleAddAndRemove(false /* R */);
    }

    // TODO: remove once presubmit tests on R even the code is submitted on S.
    @Test
    public void testTetherOffloadRuleAddAndRemoveAtLeastSdkS() throws Exception {
        checkTetherOffloadRuleAddAndRemove(true /* S+ */);
    }

    // TODO: remove once presubmit tests on R even the code is submitted on S.
    private void checkTetherOffloadGetStats(boolean usingApiS) throws Exception {
        setupFunctioningNetdInterface();

        doReturn(usingApiS).when(mDeps).isAtLeastS();
        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        updateStatsEntriesAndWaitForUpdate(new TetherStatsParcel[] {
                buildTestTetherStatsParcel(mobileIfIndex, 1000, 100, 2000, 200)});

        final NetworkStats expectedIfaceStats = new NetworkStats(0L, 1)
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 1000, 100, 2000, 200));

        final NetworkStats expectedUidStats = new NetworkStats(0L, 1)
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 1000, 100, 2000, 200));

        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStats, expectedUidStats);
    }

    // TODO: remove once presubmit tests on R even the code is submitted on S.
    @Test
    public void testTetherOffloadGetStatsSdkR() throws Exception {
        checkTetherOffloadGetStats(false /* R */);
    }

    // TODO: remove once presubmit tests on R even the code is submitted on S.
    @Test
    public void testTetherOffloadGetStatsAtLeastSdkS() throws Exception {
        checkTetherOffloadGetStats(true /* S+ */);
    }

    @Test
    public void testGetForwardedStats() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        final String wlanIface = "wlan0";
        final Integer wlanIfIndex = 100;
        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 101;

        // Add interface name to lookup table. In realistic case, the upstream interface name will
        // be added by IpServer when IpServer has received with a new IPv6 upstream update event.
        coordinator.addUpstreamNameToLookupTable(wlanIfIndex, wlanIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // [1] Both interface stats are changed.
        // Setup the tether stats of wlan and mobile interface. Note that move forward the time of
        // the looper to make sure the new tether stats has been updated by polling update thread.
        updateStatsEntriesAndWaitForUpdate(new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3000, 300, 4000, 400)});

        final NetworkStats expectedIfaceStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 3000, 300, 4000, 400));

        final NetworkStats expectedUidStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 3000, 300, 4000, 400));

        // Force pushing stats update to verify the stats reported.
        // TODO: Perhaps make #expectNotifyStatsUpdated to use test TetherStatsParcel object for
        // verifying the notification.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStats, expectedUidStats);

        // [2] Only one interface stats is changed.
        // The tether stats of mobile interface is accumulated and The tether stats of wlan
        // interface is the same.
        updateStatsEntriesAndWaitForUpdate(new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3010, 320, 4030, 440)});

        final NetworkStats expectedIfaceStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 10, 20, 30, 40));

        final NetworkStats expectedUidStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 10, 20, 30, 40));

        // Force pushing stats update to verify that only diff of stats is reported.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStatsDiff,
                expectedUidStatsDiff);

        // [3] Stop coordinator.
        // Shutdown the coordinator and clear the invocation history, especially the
        // tetherOffloadGetStats() calls.
        coordinator.stopPolling();
        clearStatsInvocations();

        // Verify the polling update thread stopped.
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        verifyNeverTetherOffloadGetStats();
    }

    @Test
    public void testOnSetAlert() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // Verify that set quota to 0 will immediately triggers a callback.
        mTetherStatsProvider.onSetAlert(0);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that notifyAlertReached never fired if quota is not yet reached.
        updateStatsEntry(buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0));
        mTetherStatsProvider.onSetAlert(100);
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();

        // Verify that notifyAlertReached fired when quota is reached.
        updateStatsEntry(buildTestTetherStatsParcel(mobileIfIndex, 50, 0, 50, 0));
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that set quota with UNLIMITED won't trigger any callback.
        mTetherStatsProvider.onSetAlert(QUOTA_UNLIMITED);
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();
    }

    // The custom ArgumentMatcher simply comes from IpServerTest.
    // TODO: move both of them into a common utility class for reusing the code.
    private static class TetherOffloadRuleParcelMatcher implements
            ArgumentMatcher<TetherOffloadRuleParcel> {
        public final int upstreamIfindex;
        public final int downstreamIfindex;
        public final Inet6Address address;
        public final MacAddress srcMac;
        public final MacAddress dstMac;

        TetherOffloadRuleParcelMatcher(@NonNull Ipv6ForwardingRule rule) {
            upstreamIfindex = rule.upstreamIfindex;
            downstreamIfindex = rule.downstreamIfindex;
            address = rule.address;
            srcMac = rule.srcMac;
            dstMac = rule.dstMac;
        }

        public boolean matches(@NonNull TetherOffloadRuleParcel parcel) {
            return upstreamIfindex == parcel.inputInterfaceIndex
                    && (downstreamIfindex == parcel.outputInterfaceIndex)
                    && Arrays.equals(address.getAddress(), parcel.destination)
                    && (128 == parcel.prefixLength)
                    && Arrays.equals(srcMac.toByteArray(), parcel.srcL2Address)
                    && Arrays.equals(dstMac.toByteArray(), parcel.dstL2Address);
        }

        public String toString() {
            return String.format("TetherOffloadRuleParcelMatcher(%d, %d, %s, %s, %s",
                    upstreamIfindex, downstreamIfindex, address.getHostAddress(), srcMac, dstMac);
        }
    }

    @NonNull
    private TetherOffloadRuleParcel matches(@NonNull Ipv6ForwardingRule rule) {
        return argThat(new TetherOffloadRuleParcelMatcher(rule));
    }

    @NonNull
    private static Ipv6ForwardingRule buildTestForwardingRule(
            int upstreamIfindex, @NonNull InetAddress address, @NonNull MacAddress dstMac) {
        return new Ipv6ForwardingRule(upstreamIfindex, DOWNSTREAM_IFINDEX, (Inet6Address) address,
                DOWNSTREAM_MAC, dstMac);
    }

    @Test
    public void testRuleMakeTetherDownstream6Key() throws Exception {
        final Integer mobileIfIndex = 100;
        final Ipv6ForwardingRule rule = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);

        final TetherDownstream6Key key = rule.makeTetherDownstream6Key();
        assertEquals(key.iif, (long) mobileIfIndex);
        assertTrue(Arrays.equals(key.neigh6, NEIGH_A.getAddress()));
        // iif (4) + neigh6 (16) = 20.
        assertEquals(20, key.writeToBytes().length);
    }

    @Test
    public void testRuleMakeTether6Value() throws Exception {
        final Integer mobileIfIndex = 100;
        final Ipv6ForwardingRule rule = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);

        final Tether6Value value = rule.makeTether6Value();
        assertEquals(value.oif, DOWNSTREAM_IFINDEX);
        assertEquals(value.ethDstMac, MAC_A);
        assertEquals(value.ethSrcMac, DOWNSTREAM_MAC);
        assertEquals(value.ethProto, ETH_P_IPV6);
        assertEquals(value.pmtu, NetworkStackConstants.ETHER_MTU);
        // oif (4) + ethDstMac (6) + ethSrcMac (6) + ethProto (2) + pmtu (2) = 20.
        assertEquals(20, value.writeToBytes().length);
    }

    @Test
    public void testSetDataLimit() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // [1] Default limit.
        // Set the unlimited quota as default if the service has never applied a data limit for a
        // given upstream. Note that the data limit only be applied on an upstream which has rules.
        final Ipv6ForwardingRule rule = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);
        final InOrder inOrder = inOrder(mNetd, mBpfDownstream6Map, mBpfLimitMap, mBpfStatsMap);
        coordinator.tetherOffloadRuleAdd(mIpServer, rule);
        verifyTetherOffloadRuleAdd(inOrder, rule);
        verifyTetherOffloadSetInterfaceQuota(inOrder, mobileIfIndex, QUOTA_UNLIMITED,
                true /* isInit */);
        inOrder.verifyNoMoreInteractions();

        // [2] Specific limit.
        // Applying the data limit boundary {min, 1gb, max, infinity} on current upstream.
        for (final long quota : new long[] {0, 1048576000, Long.MAX_VALUE, QUOTA_UNLIMITED}) {
            mTetherStatsProvider.onSetLimit(mobileIface, quota);
            waitForIdle();
            verifyTetherOffloadSetInterfaceQuota(inOrder, mobileIfIndex, quota,
                    false /* isInit */);
            inOrder.verifyNoMoreInteractions();
        }

        // [3] Invalid limit.
        // The valid range of quota is 0..max_int64 or -1 (unlimited).
        final long invalidLimit = Long.MIN_VALUE;
        try {
            mTetherStatsProvider.onSetLimit(mobileIface, invalidLimit);
            waitForIdle();
            fail("No exception thrown for invalid limit " + invalidLimit + ".");
        } catch (IllegalArgumentException expected) {
            assertEquals(expected.getMessage(), "invalid quota value " + invalidLimit);
        }
    }

    // TODO: Test the case in which the rules are changed from different IpServer objects.
    @Test
    public void testSetDataLimitOnRuleChange() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // Applying a data limit to the current upstream does not take any immediate action.
        // The data limit could be only set on an upstream which has rules.
        final long limit = 12345;
        final InOrder inOrder = inOrder(mNetd, mBpfDownstream6Map, mBpfLimitMap, mBpfStatsMap);
        mTetherStatsProvider.onSetLimit(mobileIface, limit);
        waitForIdle();
        verifyNeverTetherOffloadSetInterfaceQuota(inOrder);

        // Adding the first rule on current upstream immediately sends the quota to netd.
        final Ipv6ForwardingRule ruleA = buildTestForwardingRule(mobileIfIndex, NEIGH_A, MAC_A);
        coordinator.tetherOffloadRuleAdd(mIpServer, ruleA);
        verifyTetherOffloadRuleAdd(inOrder, ruleA);
        verifyTetherOffloadSetInterfaceQuota(inOrder, mobileIfIndex, limit, true /* isInit */);
        inOrder.verifyNoMoreInteractions();

        // Adding the second rule on current upstream does not send the quota to netd.
        final Ipv6ForwardingRule ruleB = buildTestForwardingRule(mobileIfIndex, NEIGH_B, MAC_B);
        coordinator.tetherOffloadRuleAdd(mIpServer, ruleB);
        verifyTetherOffloadRuleAdd(inOrder, ruleB);
        verifyNeverTetherOffloadSetInterfaceQuota(inOrder);

        // Removing the second rule on current upstream does not send the quota to netd.
        coordinator.tetherOffloadRuleRemove(mIpServer, ruleB);
        verifyTetherOffloadRuleRemove(inOrder, ruleB);
        verifyNeverTetherOffloadSetInterfaceQuota(inOrder);

        // Removing the last rule on current upstream immediately sends the cleanup stuff to netd.
        updateStatsEntryForTetherOffloadGetAndClearStats(
                buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0));
        coordinator.tetherOffloadRuleRemove(mIpServer, ruleA);
        verifyTetherOffloadRuleRemove(inOrder, ruleA);
        verifyTetherOffloadGetAndClearStats(inOrder, mobileIfIndex);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testTetherOffloadRuleUpdateAndClear() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        final String ethIface = "eth1";
        final String mobileIface = "rmnet_data0";
        final Integer ethIfIndex = 100;
        final Integer mobileIfIndex = 101;
        coordinator.addUpstreamNameToLookupTable(ethIfIndex, ethIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        final InOrder inOrder = inOrder(mNetd, mBpfDownstream6Map, mBpfUpstream6Map, mBpfLimitMap,
                mBpfStatsMap);

        // Before the rule test, here are the additional actions while the rules are changed.
        // - After adding the first rule on a given upstream, the coordinator adds a data limit.
        //   If the service has never applied the data limit, set an unlimited quota as default.
        // - After removing the last rule on a given upstream, the coordinator gets the last stats.
        //   Then, it clears the stats and the limit entry from BPF maps.
        // See tetherOffloadRule{Add, Remove, Clear, Clean}.

        // [1] Adding rules on the upstream Ethernet.
        // Note that the default data limit is applied after the first rule is added.
        final Ipv6ForwardingRule ethernetRuleA = buildTestForwardingRule(
                ethIfIndex, NEIGH_A, MAC_A);
        final Ipv6ForwardingRule ethernetRuleB = buildTestForwardingRule(
                ethIfIndex, NEIGH_B, MAC_B);

        coordinator.tetherOffloadRuleAdd(mIpServer, ethernetRuleA);
        verifyTetherOffloadRuleAdd(inOrder, ethernetRuleA);
        verifyTetherOffloadSetInterfaceQuota(inOrder, ethIfIndex, QUOTA_UNLIMITED,
                true /* isInit */);
        verifyStartUpstreamIpv6Forwarding(inOrder, DOWNSTREAM_IFINDEX, ethIfIndex);
        coordinator.tetherOffloadRuleAdd(mIpServer, ethernetRuleB);
        verifyTetherOffloadRuleAdd(inOrder, ethernetRuleB);

        // [2] Update the existing rules from Ethernet to cellular.
        final Ipv6ForwardingRule mobileRuleA = buildTestForwardingRule(
                mobileIfIndex, NEIGH_A, MAC_A);
        final Ipv6ForwardingRule mobileRuleB = buildTestForwardingRule(
                mobileIfIndex, NEIGH_B, MAC_B);
        updateStatsEntryForTetherOffloadGetAndClearStats(
                buildTestTetherStatsParcel(ethIfIndex, 10, 20, 30, 40));

        // Update the existing rules for upstream changes. The rules are removed and re-added one
        // by one for updating upstream interface index by #tetherOffloadRuleUpdate.
        coordinator.tetherOffloadRuleUpdate(mIpServer, mobileIfIndex);
        verifyTetherOffloadRuleRemove(inOrder, ethernetRuleA);
        verifyTetherOffloadRuleRemove(inOrder, ethernetRuleB);
        verifyStopUpstreamIpv6Forwarding(inOrder, DOWNSTREAM_IFINDEX);
        verifyTetherOffloadGetAndClearStats(inOrder, ethIfIndex);
        verifyTetherOffloadRuleAdd(inOrder, mobileRuleA);
        verifyTetherOffloadSetInterfaceQuota(inOrder, mobileIfIndex, QUOTA_UNLIMITED,
                true /* isInit */);
        verifyStartUpstreamIpv6Forwarding(inOrder, DOWNSTREAM_IFINDEX, mobileIfIndex);
        verifyTetherOffloadRuleAdd(inOrder, mobileRuleB);

        // [3] Clear all rules for a given IpServer.
        updateStatsEntryForTetherOffloadGetAndClearStats(
                buildTestTetherStatsParcel(mobileIfIndex, 50, 60, 70, 80));
        coordinator.tetherOffloadRuleClear(mIpServer);
        verifyTetherOffloadRuleRemove(inOrder, mobileRuleA);
        verifyTetherOffloadRuleRemove(inOrder, mobileRuleB);
        verifyStopUpstreamIpv6Forwarding(inOrder, DOWNSTREAM_IFINDEX);
        verifyTetherOffloadGetAndClearStats(inOrder, mobileIfIndex);

        // [4] Force pushing stats update to verify that the last diff of stats is reported on all
        // upstreams.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(
                new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, ethIface, 10, 20, 30, 40))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 50, 60, 70, 80)),
                new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, ethIface, 10, 20, 30, 40))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 50, 60, 70, 80)));
    }

    private void checkBpfDisabled() throws Exception {
        // The caller may mock the global dependencies |mDeps| which is used in
        // #makeBpfCoordinator for testing.
        // See #testBpfDisabledbyNoBpfDownstream6Map.
        final BpfCoordinator coordinator = makeBpfCoordinator();
        coordinator.startPolling();

        // The tether stats polling task should not be scheduled.
        mTestLooper.moveTimeForward(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        waitForIdle();
        verifyNeverTetherOffloadGetStats();

        // The interface name lookup table can't be added.
        final String iface = "rmnet_data0";
        final Integer ifIndex = 100;
        coordinator.addUpstreamNameToLookupTable(ifIndex, iface);
        assertEquals(0, coordinator.getInterfaceNamesForTesting().size());

        // The rule can't be added.
        final InetAddress neigh = InetAddresses.parseNumericAddress("2001:db8::1");
        final MacAddress mac = MacAddress.fromString("00:00:00:00:00:0a");
        final Ipv6ForwardingRule rule = buildTestForwardingRule(ifIndex, neigh, mac);
        coordinator.tetherOffloadRuleAdd(mIpServer, rule);
        verifyNeverTetherOffloadRuleAdd();
        LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules =
                coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNull(rules);

        // The rule can't be removed. This is not a realistic case because adding rule is not
        // allowed. That implies no rule could be removed, cleared or updated. Verify these
        // cases just in case.
        rules = new LinkedHashMap<Inet6Address, Ipv6ForwardingRule>();
        rules.put(rule.address, rule);
        coordinator.getForwardingRulesForTesting().put(mIpServer, rules);
        coordinator.tetherOffloadRuleRemove(mIpServer, rule);
        verifyNeverTetherOffloadRuleRemove();
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());

        // The rule can't be cleared.
        coordinator.tetherOffloadRuleClear(mIpServer);
        verifyNeverTetherOffloadRuleRemove();
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());

        // The rule can't be updated.
        coordinator.tetherOffloadRuleUpdate(mIpServer, rule.upstreamIfindex + 1 /* new */);
        verifyNeverTetherOffloadRuleRemove();
        verifyNeverTetherOffloadRuleAdd();
        rules = coordinator.getForwardingRulesForTesting().get(mIpServer);
        assertNotNull(rules);
        assertEquals(1, rules.size());
    }

    @Test
    public void testBpfDisabledbyConfig() throws Exception {
        setupFunctioningNetdInterface();
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(false);

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfDownstream6Map() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfDownstream6Map();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfUpstream6Map() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfUpstream6Map();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfDownstream4Map() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfDownstream4Map();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfUpstream4Map() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfUpstream4Map();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfStatsMap() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfStatsMap();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfDisabledbyNoBpfLimitMap() throws Exception {
        setupFunctioningNetdInterface();
        doReturn(null).when(mDeps).getBpfLimitMap();

        checkBpfDisabled();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testBpfMapClear() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();
        verify(mBpfDownstream4Map).clear();
        verify(mBpfUpstream4Map).clear();
        verify(mBpfDownstream6Map).clear();
        verify(mBpfUpstream6Map).clear();
        verify(mBpfStatsMap).clear();
        verify(mBpfLimitMap).clear();
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testAttachDetachBpfProgram() throws Exception {
        setupFunctioningNetdInterface();

        // Static mocking for BpfUtils.
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(BpfUtils.class)
                .startMocking();
        try {
            final String intIface1 = "wlan1";
            final String intIface2 = "rndis0";
            final String extIface = "rmnet_data0";
            final BpfUtils mockMarkerBpfUtils = staticMockMarker(BpfUtils.class);
            final BpfCoordinator coordinator = makeBpfCoordinator();

            // [1] Add the forwarding pair <wlan1, rmnet_data0>. Expect that attach both wlan1 and
            // rmnet_data0.
            coordinator.maybeAttachProgram(intIface1, extIface);
            ExtendedMockito.verify(() -> BpfUtils.attachProgram(extIface, DOWNSTREAM));
            ExtendedMockito.verify(() -> BpfUtils.attachProgram(intIface1, UPSTREAM));
            ExtendedMockito.verifyNoMoreInteractions(mockMarkerBpfUtils);
            ExtendedMockito.clearInvocations(mockMarkerBpfUtils);

            // [2] Add the forwarding pair <wlan1, rmnet_data0> again. Expect no more action.
            coordinator.maybeAttachProgram(intIface1, extIface);
            ExtendedMockito.verifyNoMoreInteractions(mockMarkerBpfUtils);
            ExtendedMockito.clearInvocations(mockMarkerBpfUtils);

            // [3] Add the forwarding pair <rndis0, rmnet_data0>. Expect that attach rndis0 only.
            coordinator.maybeAttachProgram(intIface2, extIface);
            ExtendedMockito.verify(() -> BpfUtils.attachProgram(intIface2, UPSTREAM));
            ExtendedMockito.verifyNoMoreInteractions(mockMarkerBpfUtils);
            ExtendedMockito.clearInvocations(mockMarkerBpfUtils);

            // [4] Remove the forwarding pair <rndis0, rmnet_data0>. Expect detach rndis0 only.
            coordinator.maybeDetachProgram(intIface2, extIface);
            ExtendedMockito.verify(() -> BpfUtils.detachProgram(intIface2));
            ExtendedMockito.verifyNoMoreInteractions(mockMarkerBpfUtils);
            ExtendedMockito.clearInvocations(mockMarkerBpfUtils);

            // [5] Remove the forwarding pair <wlan1, rmnet_data0>. Expect that detach both wlan1
            // and rmnet_data0.
            coordinator.maybeDetachProgram(intIface1, extIface);
            ExtendedMockito.verify(() -> BpfUtils.detachProgram(extIface));
            ExtendedMockito.verify(() -> BpfUtils.detachProgram(intIface1));
            ExtendedMockito.verifyNoMoreInteractions(mockMarkerBpfUtils);
            ExtendedMockito.clearInvocations(mockMarkerBpfUtils);
        } finally {
            mockSession.finishMocking();
        }
    }

    @Test
    public void testTetheringConfigSetPollingInterval() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        // [1] The default polling interval.
        coordinator.startPolling();
        assertEquals(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS, coordinator.getPollingInterval());
        coordinator.stopPolling();

        // [2] Expect the invalid polling interval isn't applied. The valid range of interval is
        // DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS..max_long.
        for (final int interval
                : new int[] {0, 100, DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS - 1}) {
            when(mTetherConfig.getOffloadPollInterval()).thenReturn(interval);
            coordinator.startPolling();
            assertEquals(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS, coordinator.getPollingInterval());
            coordinator.stopPolling();
        }

        // [3] Set a specific polling interval which is larger than default value.
        // Use a large polling interval to avoid flaky test because the time forwarding
        // approximation is used to verify the scheduled time of the polling thread.
        final int pollingInterval = 100_000;
        when(mTetherConfig.getOffloadPollInterval()).thenReturn(pollingInterval);
        coordinator.startPolling();

        // Expect the specific polling interval to be applied.
        assertEquals(pollingInterval, coordinator.getPollingInterval());

        // Start on a new polling time slot.
        mTestLooper.moveTimeForward(pollingInterval);
        waitForIdle();
        clearStatsInvocations();

        // Move time forward to 90% polling interval time. Expect that the polling thread has not
        // scheduled yet.
        mTestLooper.moveTimeForward((long) (pollingInterval * 0.9));
        waitForIdle();
        verifyNeverTetherOffloadGetStats();

        // Move time forward to the remaining 10% polling interval time. Expect that the polling
        // thread has scheduled.
        mTestLooper.moveTimeForward((long) (pollingInterval * 0.1));
        waitForIdle();
        verifyTetherOffloadGetStats();
    }

    @Test
    public void testStartStopConntrackMonitoring() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        // [1] Don't stop monitoring if it has never started.
        coordinator.stopMonitoring(mIpServer);
        verify(mConntrackMonitor, never()).start();

        // [2] Start monitoring.
        coordinator.startMonitoring(mIpServer);
        verify(mConntrackMonitor).start();
        clearInvocations(mConntrackMonitor);

        // [3] Stop monitoring.
        coordinator.stopMonitoring(mIpServer);
        verify(mConntrackMonitor).stop();
    }

    @Test
    public void testStartStopConntrackMonitoringWithTwoDownstreamIfaces() throws Exception {
        setupFunctioningNetdInterface();

        final BpfCoordinator coordinator = makeBpfCoordinator();

        // [1] Start monitoring at the first IpServer adding.
        coordinator.startMonitoring(mIpServer);
        verify(mConntrackMonitor).start();
        clearInvocations(mConntrackMonitor);

        // [2] Don't start monitoring at the second IpServer adding.
        coordinator.startMonitoring(mIpServer2);
        verify(mConntrackMonitor, never()).start();

        // [3] Don't stop monitoring if any downstream interface exists.
        coordinator.stopMonitoring(mIpServer2);
        verify(mConntrackMonitor, never()).stop();

        // [4] Stop monitoring if no downstream exists.
        coordinator.stopMonitoring(mIpServer);
        verify(mConntrackMonitor).stop();
    }
}