/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone.fragment;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_IN;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_OUT;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.IDLE;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.RUNNING_CHIP_ANIM;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogcatEchoTracker;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class CollapsedStatusBarFragmentTest extends SysuiBaseFragmentTest {

    private NotificationIconAreaController mMockNotificationAreaController;
    private View mNotificationAreaInner;
    private OngoingCallController mOngoingCallController;
    private SystemStatusAnimationScheduler mAnimationScheduler;
    private StatusBarLocationPublisher mLocationPublisher;
    // Set in instantiate()
    private StatusBarIconController mStatusBarIconController;
    private NetworkController mNetworkController;
    private KeyguardStateController mKeyguardStateController;

    private final CommandQueue mCommandQueue = mock(CommandQueue.class);
    private OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private OperatorNameViewController mOperatorNameViewController;
    private final CarrierConfigTracker mCarrierConfigTracker = mock(CarrierConfigTracker.class);

    @Mock
    private StatusBarFragmentComponent.Factory mStatusBarFragmentComponentFactory;
    @Mock
    private StatusBarFragmentComponent mStatusBarFragmentComponent;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    @Mock
    private NotificationPanelViewController mNotificationPanelViewController;
    @Mock
    private StatusBarIconController.DarkIconManager.Factory mIconManagerFactory;
    @Mock
    private StatusBarIconController.DarkIconManager mIconManager;
    @Mock
    private StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    @Mock
    private DumpManager mDumpManager;

    public CollapsedStatusBarFragmentTest() {
        super(CollapsedStatusBarFragment.class);
    }

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mDependency.injectMockDependency(DarkIconDispatcher.class);
    }

    @Test
    public void testDisableNone() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void testDisableSystemInfo_systemAnimationIdle_doesHide() {
        when(mAnimationScheduler.getAnimationState()).thenReturn(IDLE);
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void testSystemStatusAnimation_startedDisabled_finishedWithAnimator_showsSystemInfo() {
        // GIVEN the status bar hides the system info via disable flags, while there is no event
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mAnimationScheduler.getAnimationState()).thenReturn(IDLE);
        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the disable flags are cleared during a system event animation
        when(mAnimationScheduler.getAnimationState()).thenReturn(RUNNING_CHIP_ANIM);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN the view is made visible again, but still low alpha
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation finishes
        when(mAnimationScheduler.getAnimationState()).thenReturn(ANIMATING_OUT);
        Animator anim = fragment.onSystemEventAnimationFinish(false);
        anim.start();
        processAllMessages();
        anim.end();

        // THEN the system info is full alpha
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testSystemStatusAnimation_systemInfoDisabled_staysInvisible() {
        // GIVEN the status bar hides the system info via disable flags, while there is no event
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mAnimationScheduler.getAnimationState()).thenReturn(IDLE);
        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the system event animation finishes
        when(mAnimationScheduler.getAnimationState()).thenReturn(ANIMATING_OUT);
        Animator anim = fragment.onSystemEventAnimationFinish(false);
        anim.start();
        processAllMessages();
        anim.end();

        // THEN the system info is at full alpha, but still INVISIBLE (since the disable flag is
        // still set)
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
    }


    @Test
    public void testSystemStatusAnimation_notDisabled_animatesAlphaZero() {
        // GIVEN the status bar is not disabled
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mAnimationScheduler.getAnimationState()).thenReturn(ANIMATING_IN);
        // WHEN the system event animation begins
        Animator anim = fragment.onSystemEventAnimationBegin();
        anim.start();
        processAllMessages();
        anim.end();

        // THEN the system info is visible but alpha 0
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testSystemStatusAnimation_notDisabled_animatesBackToAlphaOne() {
        // GIVEN the status bar is not disabled
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mAnimationScheduler.getAnimationState()).thenReturn(ANIMATING_IN);
        // WHEN the system event animation begins
        Animator anim = fragment.onSystemEventAnimationBegin();
        anim.start();
        processAllMessages();
        anim.end();

        // THEN the system info is visible but alpha 0
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation finishes
        when(mAnimationScheduler.getAnimationState()).thenReturn(ANIMATING_OUT);
        anim = fragment.onSystemEventAnimationFinish(false);
        anim.start();
        processAllMessages();
        anim.end();

        // THEN the syste info is full alpha and VISIBLE
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testDisableNotifications() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.VISIBLE));
    }

    @Test
    public void testDisableClock() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_CLOCK, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_noOngoingCall_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_hasOngoingCall_chipDisplayedAndNotificationIconsHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

    }

    @Test
    public void disable_hasOngoingCallButNotificationIconsDisabled_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY,
                StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_ongoingCallEnded_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        // Ongoing call started
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);
        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());

        // Ongoing call ended
        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_isDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_NotDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_headsUpShouldBeVisibleTrue_clockDisabled() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());
    }

    @Test
    public void disable_headsUpShouldBeVisibleFalse_clockNotDisabled() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void setUp_fragmentCreatesDaggerComponent() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        assertEquals(mStatusBarFragmentComponent, fragment.getStatusBarFragmentComponent());
    }

    @Override
    protected Fragment instantiate(Context context, String className, Bundle arguments) {
        MockitoAnnotations.initMocks(this);
        setUpDaggerComponent();
        mOngoingCallController = mock(OngoingCallController.class);
        mAnimationScheduler = mock(SystemStatusAnimationScheduler.class);
        mLocationPublisher = mock(StatusBarLocationPublisher.class);
        mStatusBarIconController = mock(StatusBarIconController.class);
        mNetworkController = mock(NetworkController.class);
        mStatusBarStateController = mock(StatusBarStateController.class);
        mKeyguardStateController = mock(KeyguardStateController.class);
        mOperatorNameViewController = mock(OperatorNameViewController.class);
        mOperatorNameViewControllerFactory = mock(OperatorNameViewController.Factory.class);
        when(mOperatorNameViewControllerFactory.create(any()))
                .thenReturn(mOperatorNameViewController);
        when(mIconManagerFactory.create(any(), any())).thenReturn(mIconManager);

        setUpNotificationIconAreaController();
        return new CollapsedStatusBarFragment(
                mStatusBarFragmentComponentFactory,
                mOngoingCallController,
                mAnimationScheduler,
                mLocationPublisher,
                mMockNotificationAreaController,
                new PanelExpansionStateManager(),
                mock(FeatureFlags.class),
                mStatusBarIconController,
                mIconManagerFactory,
                mStatusBarHideIconsForBouncerManager,
                mKeyguardStateController,
                mNotificationPanelViewController,
                mNetworkController,
                mStatusBarStateController,
                mCommandQueue,
                mCarrierConfigTracker,
                new CollapsedStatusBarFragmentLogger(
                        new LogBuffer("TEST", 1, mock(LogcatEchoTracker.class)),
                        new DisableFlagsLogger()
                        ),
                mOperatorNameViewControllerFactory,
                mDumpManager);
    }

    private void setUpDaggerComponent() {
        when(mStatusBarFragmentComponentFactory.create(any()))
                .thenReturn(mStatusBarFragmentComponent);
        when(mStatusBarFragmentComponent.getHeadsUpAppearanceController())
                .thenReturn(mHeadsUpAppearanceController);
    }

    private void setUpNotificationIconAreaController() {
        mMockNotificationAreaController = mock(NotificationIconAreaController.class);

        mNotificationAreaInner = mock(View.class);

        when(mNotificationAreaInner.getLayoutParams()).thenReturn(
                new FrameLayout.LayoutParams(100, 100));
        when(mNotificationAreaInner.animate()).thenReturn(mock(ViewPropertyAnimator.class));

        when(mMockNotificationAreaController.getNotificationInnerAreaView()).thenReturn(
                mNotificationAreaInner);
    }

    private CollapsedStatusBarFragment resumeAndGetFragment() {
        mFragments.dispatchResume();
        processAllMessages();
        return (CollapsedStatusBarFragment) mFragment;
    }

    private View getClockView() {
        return mFragment.getView().findViewById(R.id.clock);
    }

    private View getEndSideContentView() {
        return mFragment.getView().findViewById(R.id.status_bar_end_side_content);
    }
}
