/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.scroll;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.view.ViewCompat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.OverScroller;
import android.widget.ScrollView;

import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.uimanager.ReactClippingViewGroup;
import com.facebook.react.uimanager.ReactClippingViewGroupHelper;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.views.view.ReactViewBackgroundManager;

import java.lang.reflect.Field;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A simple subclass of ScrollView that doesn't dispatch measure and layout to its children and has
 * a scroll listener to send scroll events to JS.
 *
 * <p>ReactScrollView only supports vertical scrolling. For horizontal scrolling,
 * use {@link ReactHorizontalScrollView}.
 */
@TargetApi(11)
public class ReactScrollView extends ScrollView implements ReactClippingViewGroup, ViewGroup.OnHierarchyChangeListener, View.OnLayoutChangeListener {

  private static @Nullable Field sScrollerField;
  private static boolean sTriedToGetScrollerField = false;

  private final OnScrollDispatchHelper mOnScrollDispatchHelper = new OnScrollDispatchHelper();
  private final @Nullable OverScroller mScroller;
  private final VelocityHelper mVelocityHelper = new VelocityHelper();
  private final Rect mRect = new Rect(); // for reuse to avoid allocation

  private boolean mActivelyScrolling;
  private @Nullable Rect mClippingRect;
  private boolean mDragging;
  private boolean mPagingEnabled = false;
  private @Nullable Runnable mPostTouchRunnable;
  private boolean mRemoveClippedSubviews;
  private boolean mScrollEnabled = true;
  private boolean mSendMomentumEvents;
  private @Nullable FpsListener mFpsListener = null;
  private @Nullable String mScrollPerfTag;
  private @Nullable Drawable mEndBackground;
  private int mEndFillColor = Color.TRANSPARENT;
  private int mSnapInterval = 0;
  private float mDecelerationRate = 0.985f;
  private @Nullable List<Integer> mSnapOffsets;
  private View mContentView;
  private ReactViewBackgroundManager mReactBackgroundManager;

  public ReactScrollView(ReactContext context) {
    this(context, null);
  }

  public ReactScrollView(ReactContext context, @Nullable FpsListener fpsListener) {
    super(context);
    mFpsListener = fpsListener;
    mReactBackgroundManager = new ReactViewBackgroundManager(this);

    mScroller = getOverScrollerFromParent();
    setOnHierarchyChangeListener(this);
    setScrollBarStyle(SCROLLBARS_OUTSIDE_OVERLAY);
  }

  @Nullable
  private OverScroller getOverScrollerFromParent() {
    OverScroller scroller;

    if (!sTriedToGetScrollerField) {
      sTriedToGetScrollerField = true;
      try {
        sScrollerField = ScrollView.class.getDeclaredField("mScroller");
        sScrollerField.setAccessible(true);
      } catch (NoSuchFieldException e) {
        Log.w(
          ReactConstants.TAG,
          "Failed to get mScroller field for ScrollView! " +
            "This app will exhibit the bounce-back scrolling bug :(");
      }
    }

    if (sScrollerField != null) {
      try {
        Object scrollerValue = sScrollerField.get(this);
        if (scrollerValue instanceof OverScroller) {
          scroller = (OverScroller) scrollerValue;
        } else {
          Log.w(
            ReactConstants.TAG,
            "Failed to cast mScroller field in ScrollView (probably due to OEM changes to AOSP)! " +
              "This app will exhibit the bounce-back scrolling bug :(");
          scroller = null;
        }
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to get mScroller from ScrollView!", e);
      }
    } else {
      scroller = null;
    }

    return scroller;
  }

  public void setSendMomentumEvents(boolean sendMomentumEvents) {
    mSendMomentumEvents = sendMomentumEvents;
  }

  public void setScrollPerfTag(@Nullable String scrollPerfTag) {
    mScrollPerfTag = scrollPerfTag;
  }

  public void setScrollEnabled(boolean scrollEnabled) {
    mScrollEnabled = scrollEnabled;
  }

  public void setPagingEnabled(boolean pagingEnabled) {
    mPagingEnabled = pagingEnabled;
  }

  public void setDecelerationRate(float decelerationRate) {
    mDecelerationRate = decelerationRate;

    if (mScroller != null) {
      mScroller.setFriction(1.0f - mDecelerationRate);
    }
  }

  public void setSnapInterval(int snapInterval) {
    mSnapInterval = snapInterval;
  }

  public void setSnapOffsets(List<Integer> snapOffsets) {
    mSnapOffsets = snapOffsets;
  }

  public void flashScrollIndicators() {
    awakenScrollBars();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);

    setMeasuredDimension(
        MeasureSpec.getSize(widthMeasureSpec),
        MeasureSpec.getSize(heightMeasureSpec));
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    // Call with the present values in order to re-layout if necessary
    scrollTo(getScrollX(), getScrollY());
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onScrollChanged(int x, int y, int oldX, int oldY) {
    super.onScrollChanged(x, y, oldX, oldY);

    mActivelyScrolling = true;

    if (mOnScrollDispatchHelper.onScrollChanged(x, y)) {
      if (mRemoveClippedSubviews) {
        updateClippingRect();
      }

      ReactScrollViewHelper.emitScrollEvent(
        this,
        mOnScrollDispatchHelper.getXFlingVelocity(),
        mOnScrollDispatchHelper.getYFlingVelocity());
    }
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    try {
      if (super.onInterceptTouchEvent(ev)) {
        NativeGestureUtil.notifyNativeGestureStarted(this, ev);
        ReactScrollViewHelper.emitScrollBeginDragEvent(this);
        mDragging = true;
        enableFpsListener();
        return true;
      }
    } catch (IllegalArgumentException e) {
      // Log and ignore the error. This seems to be a bug in the android SDK and
      // this is the commonly accepted workaround.
      // https://tinyurl.com/mw6qkod (Stack Overflow)
      Log.w(ReactConstants.TAG, "Error intercepting touch event.", e);
    }

    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    mVelocityHelper.calculateVelocity(ev);
    int action = ev.getAction() & MotionEvent.ACTION_MASK;
    if (action == MotionEvent.ACTION_UP && mDragging) {
      float velocityX = mVelocityHelper.getXVelocity();
      float velocityY = mVelocityHelper.getYVelocity();
      ReactScrollViewHelper.emitScrollEndDragEvent(
        this,
        velocityX,
        velocityY);
      mDragging = false;
      // After the touch finishes, we may need to do some scrolling afterwards either as a result
      // of a fling or because we need to page align the content
      handlePostTouchScrolling(Math.round(velocityX), Math.round(velocityY));
    }

    return super.onTouchEvent(ev);
  }

  @Override
  public void setRemoveClippedSubviews(boolean removeClippedSubviews) {
    if (removeClippedSubviews && mClippingRect == null) {
      mClippingRect = new Rect();
    }
    mRemoveClippedSubviews = removeClippedSubviews;
    updateClippingRect();
  }

  @Override
  public boolean getRemoveClippedSubviews() {
    return mRemoveClippedSubviews;
  }

  @Override
  public void updateClippingRect() {
    if (!mRemoveClippedSubviews) {
      return;
    }

    Assertions.assertNotNull(mClippingRect);

    ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
    View contentView = getChildAt(0);
    if (contentView instanceof ReactClippingViewGroup) {
      ((ReactClippingViewGroup) contentView).updateClippingRect();
    }
  }

  @Override
  public void getClippingRect(Rect outClippingRect) {
    outClippingRect.set(Assertions.assertNotNull(mClippingRect));
  }

  @Override
  public void fling(int velocityY) {

    velocityY = (int)(Math.abs(velocityY) * Math.signum(mOnScrollDispatchHelper.getYFlingVelocity()));
    
    if (mPagingEnabled) {
      smoothScrollAndSnap(velocityY);
    } else if (mScroller != null) {
      // FB SCROLLVIEW CHANGE

      // We provide our own version of fling that uses a different call to the standard OverScroller
      // which takes into account the possibility of adding new content while the ScrollView is
      // animating. Because we give essentially no max Y for the fling, the fling will continue as long
      // as there is content. See #onOverScrolled() to see the second part of this change which properly
      // aborts the scroller animation when we get to the bottom of the ScrollView content.

      int scrollWindowHeight = getHeight() - getPaddingBottom() - getPaddingTop();

      mScroller.fling(
        getScrollX(), // startX
        getScrollY(), // startY
        0, // velocityX
        velocityY, // velocityY
        0, // minX
        0, // maxX
        0, // minY
        Integer.MAX_VALUE, // maxY
        0, // overX
        scrollWindowHeight / 2 // overY
      );

      ViewCompat.postInvalidateOnAnimation(this);

      // END FB SCROLLVIEW CHANGE
    } else {
      super.fling(velocityY);
    }
    handlePostTouchScrolling(0, velocityY);
  }

  private void enableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.enable(mScrollPerfTag);
    }
  }

  private void disableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.disable(mScrollPerfTag);
    }
  }

  private boolean isScrollPerfLoggingEnabled() {
    return mFpsListener != null && mScrollPerfTag != null && !mScrollPerfTag.isEmpty();
  }

  private int getMaxScrollY() {
    int contentHeight = mContentView.getHeight();
    int viewportHeight = getHeight() - getPaddingBottom() - getPaddingTop();
    return Math.max(0, contentHeight - viewportHeight);
  }

  @Override
  public void draw(Canvas canvas) {
    if (mEndFillColor != Color.TRANSPARENT) {
      final View content = getChildAt(0);
      if (mEndBackground != null && content != null && content.getBottom() < getHeight()) {
        mEndBackground.setBounds(0, content.getBottom(), getWidth(), getHeight());
        mEndBackground.draw(canvas);
      }
    }
    getDrawingRect(mRect);
    canvas.clipRect(mRect);
    super.draw(canvas);
  }

  /**
   * This handles any sort of scrolling that may occur after a touch is finished.  This may be
   * momentum scrolling (fling) or because you have pagingEnabled on the scroll view.  Because we
   * don't get any events from Android about this lifecycle, we do all our detection by creating a
   * runnable that checks if we scrolled in the last frame and if so assumes we are still scrolling.
   */
  private void handlePostTouchScrolling(int velocityX, int velocityY) {
    // If we aren't going to do anything (send events or snap to page), we can early exit out.
    if (!mSendMomentumEvents && !mPagingEnabled && !isScrollPerfLoggingEnabled()) {
      return;
    }

    // Check if we are already handling this which may occur if this is called by both the touch up
    // and a fling call
    if (mPostTouchRunnable != null) {
      return;
    }

    if (mSendMomentumEvents) {
      enableFpsListener();
      ReactScrollViewHelper.emitScrollMomentumBeginEvent(this, velocityX, velocityY);
    }

    mActivelyScrolling = false;
    mPostTouchRunnable = new Runnable() {

      private boolean mSnappingToPage = false;

      @Override
      public void run() {
        if (mActivelyScrolling) {
          // We are still scrolling so we just post to check again a frame later
          mActivelyScrolling = false;
          ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
            this,
            ReactScrollViewHelper.MOMENTUM_DELAY);
        } else {
          if (mPagingEnabled && !mSnappingToPage) {
            // Only if we have pagingEnabled and we have not snapped to the page do we
            // need to continue checking for the scroll.  And we cause that scroll by asking for it
            mSnappingToPage = true;
            smoothScrollAndSnap(0);
            ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
              this,
              ReactScrollViewHelper.MOMENTUM_DELAY);
          } else {
            if (mSendMomentumEvents) {
              ReactScrollViewHelper.emitScrollMomentumEndEvent(ReactScrollView.this);
            }
            ReactScrollView.this.mPostTouchRunnable = null;
            disableFpsListener();
          }
        }
      }
    };
    ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
      mPostTouchRunnable,
      ReactScrollViewHelper.MOMENTUM_DELAY);
  }

  /**
   * This will smooth scroll us to the nearest snap offset point
   * It currently just looks at where the content is and slides to the nearest point.
   * It is intended to be run after we are done scrolling, and handling any momentum scrolling.
   */
  private void smoothScrollAndSnap(int velocityY) {
    if (getChildCount() <= 0) {
      return;
    }

    int maximumOffset = getMaxScrollY();
    int targetOffset = 0;
    int smallerOffset = 0;
    int largerOffset = maximumOffset;

    // ScrollView can *only* scroll for 250ms when using smoothScrollTo and there's
    // no way to customize the scroll duration. So, we create a temporary OverScroller
    // so we can predict where a fling would land and snap to nearby that point.
    OverScroller scroller = new OverScroller(getContext());
    scroller.setFriction(1.0f - mDecelerationRate);

    // predict where a fling would end up so we can scroll to the nearest snap offset
    int height = getHeight() - getPaddingBottom() - getPaddingTop();
    scroller.fling(
      getScrollX(), // startX
      getScrollY(), // startY
      0, // velocityX
      velocityY, // velocityY
      0, // minX
      0, // maxX
      0, // minY
      maximumOffset, // maxY
      0, // overX
      height/2 // overY
    );
    targetOffset = scroller.getFinalY();

    // get the nearest snap points to the target offset
    if (mSnapOffsets != null) {
      for (int i = 0; i < mSnapOffsets.size(); i ++) {
        int offset = mSnapOffsets.get(i);

        if (offset <= targetOffset) {
          if (targetOffset - offset < targetOffset - smallerOffset) {
            smallerOffset = offset;
          }
        }

        if (offset >= targetOffset) {
          if (offset - targetOffset < largerOffset - targetOffset) {
            largerOffset = offset;
          }
        }
      }
    } else {
      double interval = (double) getSnapInterval();
      double ratio = (double) targetOffset / interval;
      smallerOffset = (int) (Math.floor(ratio) * interval);
      largerOffset = (int) (Math.ceil(ratio) * interval);
    }

    // Calculate the nearest offset
    int nearestOffset = targetOffset - smallerOffset < largerOffset - targetOffset
      ? smallerOffset
      : largerOffset;

    // Chose the correct snap offset based on velocity
    if (velocityY > 0) {
      targetOffset = largerOffset;
    } else if (velocityY < 0) {
      targetOffset = smallerOffset;
    } else {
      targetOffset = nearestOffset;
    }

    // Make sure the new offset isn't out of bounds
    targetOffset = Math.min(Math.max(0, targetOffset), maximumOffset);

    // smoothScrollTo will always scroll over 250ms which is often *waaay*
    // too short and will cause the scrolling to feel almost instant
    // try to manually interact with OverScroller instead
    // if velocity is 0 however, fling() won't work, so we want to use smoothScrollTo
    if (mScroller != null) {
      mActivelyScrolling = true;

      mScroller.fling(
        getScrollX(), // startX
        getScrollY(), // startY
        // velocity = 0 doesn't work with fling() so we pretend there's a reasonable
        // initial velocity going on when a touch is released without any movement
        0, // velocityX
        velocityY != 0 ? velocityY : targetOffset - getScrollY(), // velocityY
        0, // minX
        0, // maxX
        // setting both minY and maxY to the same value will guarantee that we scroll to it
        // but using the standard fling-style easing rather than smoothScrollTo's 250ms animation
        targetOffset, // minY
        targetOffset, // maxY
        0, // overX
        // we only want to allow overscrolling if the final offset is at the very edge of the view
        (targetOffset == 0 || targetOffset == maximumOffset) ? height / 2 : 0 // overY
      );

      postInvalidateOnAnimation();
    } else {
      smoothScrollTo(getScrollX(), targetOffset);
    }
  }

  private int getSnapInterval() {
    if (mSnapInterval != 0) {
      return mSnapInterval;
    }
    return getHeight();
  }

  public void setEndFillColor(int color) {
    if (color != mEndFillColor) {
      mEndFillColor = color;
      mEndBackground = new ColorDrawable(mEndFillColor);
    }
  }

  @Override
  protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
    if (mScroller != null) {
      // FB SCROLLVIEW CHANGE

      // This is part two of the reimplementation of fling to fix the bounce-back bug. See #fling() for
      // more information.

      if (!mScroller.isFinished() && mScroller.getCurrY() != mScroller.getFinalY()) {
        int scrollRange = getMaxScrollY();
        if (scrollY >= scrollRange) {
          mScroller.abortAnimation();
          scrollY = scrollRange;
        }
      }

      // END FB SCROLLVIEW CHANGE
    }

    super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
  }

  @Override
  public void onChildViewAdded(View parent, View child) {
    mContentView = child;
    mContentView.addOnLayoutChangeListener(this);
  }

  @Override
  public void onChildViewRemoved(View parent, View child) {
    mContentView.removeOnLayoutChangeListener(this);
    mContentView = null;
  }

  /**
   * Called when a mContentView's layout has changed. Fixes the scroll position if it's too large
   * after the content resizes. Without this, the user would see a blank ScrollView when the scroll
   * position is larger than the ScrollView's max scroll position after the content shrinks.
   */
  @Override
  public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
    if (mContentView == null) {
      return;
    }

    int currentScrollY = getScrollY();
    int maxScrollY = getMaxScrollY();
    if (currentScrollY > maxScrollY) {
      scrollTo(getScrollX(), maxScrollY);
    }
  }

  @Override
  public void setBackgroundColor(int color) {
    mReactBackgroundManager.setBackgroundColor(color);
  }

  public void setBorderWidth(int position, float width) {
    mReactBackgroundManager.setBorderWidth(position, width);
  }

  public void setBorderColor(int position, float color, float alpha) {
    mReactBackgroundManager.setBorderColor(position, color, alpha);
  }

  public void setBorderRadius(float borderRadius) {
    mReactBackgroundManager.setBorderRadius(borderRadius);
  }

  public void setBorderRadius(float borderRadius, int position) {
    mReactBackgroundManager.setBorderRadius(borderRadius, position);
  }

  public void setBorderStyle(@Nullable String style) {
    mReactBackgroundManager.setBorderStyle(style);
  }

}
