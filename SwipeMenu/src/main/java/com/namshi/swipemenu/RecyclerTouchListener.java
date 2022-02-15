package com.namshi.swipemenu;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecyclerTouchListener implements RecyclerView.OnItemTouchListener, OnActivityTouchListener {
    private static final String TAG = "RecyclerTouchListener";
    Activity act;
    /*
     * independentViews are views on the foreground layer which when clicked, act "independent" from the foreground
     * ie, they are treated separately from the "row click" action
     */
    List<Integer> independentViews;
    List<Integer> optionViews;
    Set<Integer> ignoredViewTypes;
    // Cached ViewConfiguration and system-wide constant values
    private final int touchSlop;
    private final int minFlingVel;
    private final int maxFlingVel;
    private static final long ANIMATION_STANDARD = 300L;
    private static final long ANIMATION_CLOSE = 150L;
    private static final int VERTICAL_THRESHOLD = 15;
    // Fixed properties
    private final RecyclerView rView;
    private int bgWidth = 1; // 1 and not 0 to prevent dividing by zero
    // Transient properties
    private float touchedX;
    private float touchedY;
    private boolean isFgSwiping;
    private int mSwipingSlop;
    private VelocityTracker mVelocityTracker;
    private int touchedPosition;
    private View touchedView;
    private boolean mPaused;
    private boolean bgVisible, fgPartialViewClicked;
    private int bgVisiblePosition;
    private View bgVisibleView;
    private boolean isRViewScrolling;
    private int heightOutsideRView, screenHeight;
    private final boolean isRtl;

    // Foreground view (to be swiped), Background view (to show)
    private View fgView;
    private View bgView;
    //view ID
    private int fgViewID;
    private int bgViewID;
    private ArrayList<Integer> fadeViews;
    private OnRowClickListener mRowClickListener;
    private OnSwipeOptionsClickListener mBgClickListener;
    // user choices
    private boolean clickable = false;

    public RecyclerTouchListener(Activity a, RecyclerView recyclerView, boolean isRtl) {
        this.act = a;
        ViewConfiguration vc = ViewConfiguration.get(a.getBaseContext());
        touchSlop = vc.getScaledTouchSlop();
        minFlingVel = vc.getScaledMinimumFlingVelocity() * 16;
        maxFlingVel = vc.getScaledMaximumFlingVelocity();
        rView = recyclerView;
        bgVisible = false;
        bgVisiblePosition = RecyclerView.NO_POSITION;
        bgVisibleView = null;
        fgPartialViewClicked = false;
        independentViews = new ArrayList<>();
        optionViews = new ArrayList<>();
        fadeViews = new ArrayList<>();
        ignoredViewTypes = new HashSet<>();
        isRViewScrolling = false;
        this.isRtl = isRtl;

        rView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                /*
                 * This will ensure that this RecyclerTouchListener is paused during recycler view scrolling.
                 * If a scroll listener is already assigned, the caller should still pass scroll changes through
                 * to this listener.
                 */
                setEnabled(newState != RecyclerView.SCROLL_STATE_DRAGGING);

                /*
                  This is used so that clicking a row cannot be done while scrolling
                 */
                isRViewScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // Do nothing
            }
        });
    }

    /**
     * Enables or disables (pauses or resumes) watching for swipe-to-dismiss gestures.
     *
     * @param enabled Whether or not to watch for gestures.
     */
    public void setEnabled(boolean enabled) {
        mPaused = !enabled;
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent motionEvent) {
        return handleTouchEvent(rv, motionEvent);
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent motionEvent) {
        handleTouchEvent(rv, motionEvent);
    }

    /*////////////// Clickable ////////////////////*/

    @NonNull
    public RecyclerTouchListener setClickable(OnRowClickListener listener) {
        this.clickable = true;
        this.mRowClickListener = listener;
        return this;
    }

    @NonNull
    public RecyclerTouchListener setClickable(boolean clickable) {
        this.clickable = clickable;
        return this;
    }

    @NonNull
    public RecyclerTouchListener setIndependentViews(Integer... viewIds) {
        this.independentViews = Arrays.asList(viewIds);
        return this;
    }

    @NonNull
    public RecyclerTouchListener setIgnoredViewTypes(Integer... viewTypes) {
        ignoredViewTypes.clear();
        ignoredViewTypes.addAll(Arrays.asList(viewTypes));
        return this;
    }

    //////////////// Swipeable ////////////////////

    public RecyclerTouchListener setSwipeable(int foregroundID, int backgroundID, OnSwipeOptionsClickListener listener) {
        if (fgViewID != 0 && foregroundID != fgViewID)
            throw new IllegalArgumentException("foregroundID does not match previously set ID");
        fgViewID = foregroundID;
        bgViewID = backgroundID;
        this.mBgClickListener = listener;

        if (act instanceof RecyclerTouchListenerHelper)
            ((RecyclerTouchListenerHelper) act).setOnActivityTouchListener(this);

        DisplayMetrics displaymetrics = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        screenHeight = displaymetrics.heightPixels;

        return this;
    }

    public RecyclerTouchListener setSwipeOptionViews(Integer... viewIds) {
        this.optionViews = new ArrayList<>(Arrays.asList(viewIds));
        return this;
    }

    //////////////// Fade Views ////////////////////

    // Set views which are faded out as fg is opened
    public RecyclerTouchListener setViewsToFade(Integer... viewIds) {
        this.fadeViews = new ArrayList<>(Arrays.asList(viewIds));
        return this;
    }

    // the entire foreground is faded out as it is opened
    public RecyclerTouchListener setFgFade() {
        if (!fadeViews.contains(fgViewID))
            this.fadeViews.add(fgViewID);
        return this;
    }

    //-------------- Checkers for preventing ---------------//

    private boolean isIndependentViewClicked(MotionEvent motionEvent) {
        for (int i = 0; i < independentViews.size(); i++) {
            if (touchedView != null) {
                Rect rect = new Rect();
                int x = (int) motionEvent.getRawX();
                int y = (int) motionEvent.getRawY();
                touchedView.findViewById(independentViews.get(i)).getGlobalVisibleRect(rect);
                if (rect.contains(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int getOptionViewID(MotionEvent motionEvent) {
        for (int i = 0; i < optionViews.size(); i++) {
            if (touchedView != null) {
                Rect rect = new Rect();
                int x = (int) motionEvent.getRawX();
                int y = (int) motionEvent.getRawY();
                touchedView.findViewById(optionViews.get(i)).getGlobalVisibleRect(rect);
                if (rect.contains(x, y)) {
                    return optionViews.get(i);
                }
            }
        }
        return View.NO_ID;
    }

    private int getIndependentViewID(MotionEvent motionEvent) {
        for (int i = 0; i < independentViews.size(); i++) {
            if (touchedView != null) {
                Rect rect = new Rect();
                int x = (int) motionEvent.getRawX();
                int y = (int) motionEvent.getRawY();
                touchedView.findViewById(independentViews.get(i)).getGlobalVisibleRect(rect);
                if (rect.contains(x, y)) {
                    return independentViews.get(i);
                }
            }
        }
        return View.NO_ID;
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Do nothing
    }

    public void invalidateSwipeOptions() {
        bgWidth = 1;
    }

    public void openSwipeOptions(int position) {
        if (rView.getChildAt(position) == null || shouldIgnoreAction(position))
            return;
        if (bgWidth < 2) {
            if (act.findViewById(bgViewID) != null)
                bgWidth = act.findViewById(bgViewID).getWidth();
            heightOutsideRView = screenHeight - rView.getHeight();
        }
        touchedPosition = position;
        touchedView = rView.getChildAt(position);
        fgView = touchedView.findViewById(fgViewID);
        bgView = touchedView.findViewById(bgViewID);
        bgView.setMinimumHeight(fgView.getHeight());

        closeVisibleBG(null);
        animateFG(touchedView, Animation.OPEN, ANIMATION_STANDARD);
        bgVisible = true;
        bgVisibleView = fgView;
        bgVisiblePosition = touchedPosition;
    }

    public void closeVisibleBG(@Nullable final OnSwipeListener mSwipeCloseListener) {
        if (bgVisibleView == null) {
            Log.e(TAG, "No rows found for which background options are visible");
            return;
        }
        final ObjectAnimator translateAnimator = ObjectAnimator.ofFloat(bgVisibleView,
                View.TRANSLATION_X, 0f);
        translateAnimator.setDuration(ANIMATION_CLOSE);
        translateAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        translateAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                // Do nothing
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mSwipeCloseListener != null)
                    mSwipeCloseListener.onSwipeOptionsClosed();
                translateAnimator.removeAllListeners();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // Do nothing
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                // Do nothing
            }
        });
        translateAnimator.start();

        animateFadeViews(bgVisibleView, 1f, ANIMATION_CLOSE);
        bgVisible = false;
        bgVisibleView = null;
        bgVisiblePosition = RecyclerView.NO_POSITION;
    }

    private void animateFadeViews(View downView, float alpha, long duration) {
        if (fadeViews != null) {
            for (final int viewID : fadeViews) {
                downView.findViewById(viewID).animate()
                        .alpha(alpha)
                        .setDuration(duration);
            }
        }
    }

    private void animateFG(View downView, Animation animateType, long duration) {
        if (animateType == Animation.OPEN) {
            ObjectAnimator translateAnimator;
            if (!isRtl) {
                translateAnimator = ObjectAnimator.ofFloat(
                        fgView, View.TRANSLATION_X, -bgWidth);
            } else {
                translateAnimator = ObjectAnimator.ofFloat(
                        fgView, View.TRANSLATION_X, bgWidth);
            }
            translateAnimator.setDuration(duration);
            translateAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            translateAnimator.start();
            animateFadeViews(downView, 0f, duration);
        } else if (animateType == Animation.CLOSE) {
            ObjectAnimator translateAnimator = ObjectAnimator.ofFloat(
                        fgView, View.TRANSLATION_X, 0f);
            translateAnimator.setDuration(duration);
            translateAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            translateAnimator.start();
            animateFadeViews(downView, 1f, duration);
        }
    }

    private void animateFG(View downView, final Animation animateType, long duration,
                           final OnSwipeListener mSwipeCloseListener) {
        final ObjectAnimator translateAnimator;
        if (animateType == Animation.OPEN) {
            if (!isRtl) {
                translateAnimator = ObjectAnimator.ofFloat(
                        fgView, View.TRANSLATION_X, -bgWidth);
            } else {
                translateAnimator = ObjectAnimator.ofFloat(
                        fgView, View.TRANSLATION_X, bgWidth);
            }
            translateAnimator.setDuration(duration);
            translateAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            translateAnimator.start();
            animateFadeViews(downView, 0f, duration);
        } else {
            translateAnimator = ObjectAnimator.ofFloat(fgView, View.TRANSLATION_X, 0f);
            translateAnimator.setDuration(duration);
            translateAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            translateAnimator.start();
            animateFadeViews(downView, 1f, duration);
        }

        translateAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                // Do nothing
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mSwipeCloseListener != null) {
                    if (animateType == Animation.OPEN)
                        mSwipeCloseListener.onSwipeOptionsOpened();
                    else if (animateType == Animation.CLOSE)
                        mSwipeCloseListener.onSwipeOptionsClosed();
                }
                translateAnimator.removeAllListeners();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // Do nothing
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                // Do nothing
            }
        });
    }

    private boolean handleTouchEvent(RecyclerView rv, MotionEvent motionEvent) {
        if (bgWidth < 2) {
            if (rView.getRootView().findViewById(bgViewID) != null)
                bgWidth = rView.getRootView().findViewById(bgViewID).getWidth();

            heightOutsideRView = screenHeight - rView.getHeight();
        }

        switch (motionEvent.getActionMasked()) {

            // When finger touches screen
            case MotionEvent.ACTION_DOWN: {
                if (mPaused) {
                    break;
                }

                // Find the child view that was touched (perform a hit test)
                Rect rect = new Rect();
                int childCount = rView.getChildCount();
                int[] listViewCoords = new int[2];
                rView.getLocationOnScreen(listViewCoords);
                // x and y values respective to the recycler view
                int x = (int) motionEvent.getRawX() - listViewCoords[0];
                int y = (int) motionEvent.getRawY() - listViewCoords[1];
                View child;

                /*
                 * check for every child (row) in the recycler view whether the touched co-ordinates belong to that
                 * respective child and if it does, register that child as the touched view (touchedView)
                 */
                for (int i = 0; i < childCount; i++) {
                    child = rView.getChildAt(i);
                    child.getHitRect(rect);
                    if (rect.contains(x, y)) {
                        touchedPosition = i;
                        touchedView = child;
                        break;
                    }
                }

                if (touchedView != null) {
                    touchedX = motionEvent.getRawX();
                    touchedY = motionEvent.getRawY();

                    if (rView.getChildAt(touchedPosition) == null || shouldIgnoreAction(touchedPosition)) {
                        touchedPosition = RecyclerView.NO_POSITION;
                        return false;   // <-- guard here allows for ignoring events, allowing more than one view type and preventing NPE
                    }

                        mVelocityTracker = VelocityTracker.obtain();
                        mVelocityTracker.addMovement(motionEvent);
                        fgView = touchedView.findViewById(fgViewID);
                        bgView = touchedView.findViewById(bgViewID);
                        bgView.setMinimumHeight(fgView.getHeight());

                        /*
                         * bgVisible is true when the options menu is opened
                         * This block is to register fgPartialViewClicked status - Partial view is the view that is still
                         * shown on the screen if the options width is < device width
                         */
                        if (bgVisible && fgView != null) {
                            x = (int) motionEvent.getRawX();
                            y = (int) motionEvent.getRawY();
                            fgView.getGlobalVisibleRect(rect);
                            // fgPartialViewClicked = rect.contains(x, y);
                            fgPartialViewClicked = false;
                        } else {
                            fgPartialViewClicked = false;
                        }
                }

                /*
                 * If options menu is shown and the touched position is not the same as the row for which the
                 * options is displayed - close the options menu for the row which is displaying it
                 * (bgVisibleView and bgVisiblePosition is used for this purpose which registers which view and
                 * which position has it's options menu opened)
                 */
                rView.getHitRect(rect);
                if (bgVisible && touchedPosition != bgVisiblePosition) {
                    closeVisibleBG(null);
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {

                if (mVelocityTracker == null) {
                    break;
                }
                    if (touchedView != null && isFgSwiping) {
                        // cancel
                        closeVisibleBG(null);
                    }
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                    isFgSwiping = false;
                    bgView = null;
                touchedX = 0;
                touchedY = 0;
                touchedView = null;
                touchedPosition = RecyclerView.NO_POSITION;
                break;
            }

            // When finger is lifted off the screen (after clicking, flinging, swiping, etc..)
            case MotionEvent.ACTION_UP: {
                if (mVelocityTracker == null) {
                    break;
                }
                if (touchedPosition < 0)
                    break;

                // swipedLeft and swipedRight are true if the user swipes in the respective direction (no conditions)
                boolean swipedLeft = false;
                boolean swipedRight = false;
                /*
                 * swipedLeftProper and swipedRightProper are true if user swipes in the respective direction
                 * and if certain conditions are satisfied (given some few lines below)
                 */
                boolean swipedLeftProper = false;
                boolean swipedRightProper = false;

                float mFinalDelta = motionEvent.getRawX() - touchedX;

                // if swiped in a direction, make that respective variable true
                if (isFgSwiping) {
                    swipedLeft = mFinalDelta < 0;
                    swipedRight = mFinalDelta > 0;
                }

                /*
                 * If the user has swiped more than half of the width of the options menu, or if the
                 * velocity of swiping is between min and max fling values
                 * "proper" variable are set true
                 */
                if (Math.abs(mFinalDelta) > Math.round((double)bgWidth / 3) && isFgSwiping) {
                    swipedLeftProper = mFinalDelta < 0;
                    swipedRightProper = mFinalDelta > 0;
                } else {
                    mVelocityTracker.addMovement(motionEvent);
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float velocityX = mVelocityTracker.getXVelocity();
                    float absVelocityX = Math.abs(velocityX);
                    float absVelocityY = Math.abs(mVelocityTracker.getYVelocity());
                    if (minFlingVel <= absVelocityX && absVelocityX <= maxFlingVel
                            && absVelocityY < absVelocityX && isFgSwiping) {
                        // dismiss only if flinging in the same direction as dragging
                        swipedLeftProper = (velocityX < 0) == (mFinalDelta < 0);
                        swipedRightProper = (velocityX > 0) == (mFinalDelta > 0);
                    }
                }

                ///////// Manipulation of view based on the 4 variables mentioned above ///////////

                // if swiped left properly and options menu isn't already visible, animate the foreground to the left
                if (swipedLeftProper && touchedPosition != RecyclerView.NO_POSITION
                        && !bgVisible) {

                    final int downPosition = touchedPosition;
                    //TODO - speed
                    animateFG(touchedView, isRtl ? Animation.CLOSE : Animation.OPEN, ANIMATION_STANDARD);
                    bgVisible = true;
                    bgVisibleView = fgView;
                    bgVisiblePosition = downPosition;
                }
                // else if swiped right properly when options menu is visible, close the menu and bring the foreground
                // to it's original position
                else if (swipedRightProper && touchedPosition != RecyclerView.NO_POSITION
                        && !bgVisible) {
                    // dismiss
                    //TODO - speed
                    animateFG(touchedView, isRtl ? Animation.OPEN : Animation.CLOSE, ANIMATION_STANDARD);
                    bgVisible = true;
                    bgVisibleView = fgView;
                    bgVisiblePosition = touchedPosition;
                }
                // else if swiped left incorrectly (not satisfying the above conditions), animate the foreground back to
                // it's original position (spring effect)
                else if (swipedRight || swipedLeft) {
                    // cancel
                    animateFG(touchedView, Animation.CLOSE, ANIMATION_STANDARD);
                    bgVisible = false;
                    bgVisibleView = null;
                    bgVisiblePosition = RecyclerView.NO_POSITION;
                }

                // if clicked
                else if (!swipedRight && !swipedLeft) {
                    // if partial foreground view is clicked (see ACTION_DOWN) bring foreground back to original position
                    // bgVisible is true automatically since it's already checked in ACTION_DOWN block
                    if (fgPartialViewClicked) {
                        animateFG(touchedView, Animation.CLOSE, ANIMATION_STANDARD);
                        bgVisible = false;
                        bgVisibleView = null;
                        bgVisiblePosition = RecyclerView.NO_POSITION;
                    }
                    // On Click listener for rows
                    else if (clickable && !bgVisible && touchedPosition >= 0 &&
                            isIndependentViewClicked(motionEvent) && !isRViewScrolling) {
                        mRowClickListener.onRowClicked(touchedPosition);
                    }
                    // On Click listener for independent views inside the rows
                    else if (clickable && !bgVisible && touchedPosition >= 0 &&
                            !isIndependentViewClicked(motionEvent) && !isRViewScrolling) {
                        final int independentViewID = getIndependentViewID(motionEvent);
                        if (independentViewID >= 0)
                            mRowClickListener.onIndependentViewClicked(independentViewID, touchedPosition);
                    }
                    // On Click listener for background options
                    else if (bgVisible && !fgPartialViewClicked) {
                        final int optionID = getOptionViewID(motionEvent);
                        if (optionID >= 0 && touchedPosition >= 0) {
                            final int downPosition = touchedPosition;
                            closeVisibleBG(new OnSwipeListener() {
                                @Override
                                public void onSwipeOptionsClosed() {
                                    mBgClickListener.onSwipeOptionClicked(optionID, downPosition);
                                }

                                @Override
                                public void onSwipeOptionsOpened() {
                                    // Do nothing
                                }
                            });
                        }
                    }
                }
            }
            // if clicked and not swiped

                mVelocityTracker.recycle();
                mVelocityTracker = null;
            touchedX = 0;
            touchedY = 0;
            touchedView = null;
            touchedPosition = RecyclerView.NO_POSITION;
            isFgSwiping = false;
            bgView = null;
            break;

            // when finger is moving across the screen (and not yet lifted)
            case MotionEvent.ACTION_MOVE: {
                if (mVelocityTracker == null || mPaused) {
                    break;
                }

                mVelocityTracker.addMovement(motionEvent);
                float deltaX = motionEvent.getRawX() - touchedX;
                float deltaY = motionEvent.getRawY() - touchedY;

                /*
                 * isFgSwiping variable which is set to true here is used to alter the swipedLeft, swipedRightProper
                 * variables in "ACTION_UP" block by checking if user is actually swiping at present or not
                 */
                if (!isFgSwiping && Math.abs(deltaX) > touchSlop && Math.abs(deltaY) < Math.abs(deltaX) / 2) {
                    isFgSwiping = true;
                    mSwipingSlop = (deltaX > 0 ? touchSlop : -touchSlop);
                }

                // Sometime if long click and swipe both same time menu not closing
                if (Math.abs(deltaY) > Math.abs(deltaX) + VERTICAL_THRESHOLD) {
                    closeVisibleBG(null);
                }

                // This block moves the foreground along with the finger when swiping
                if (isFgSwiping) {
                    if (bgView == null) {
                        bgView = touchedView.findViewById(bgViewID);
                        bgView.setVisibility(View.VISIBLE);
                    }

                    // if fg is being swiped left
                    if (deltaX < touchSlop) {
                        float translateAmount = deltaX - mSwipingSlop;

                        if (!isRtl && !bgVisible) {
                            fgView.setTranslationX(Math.abs(translateAmount) > bgWidth ? -bgWidth : translateAmount);
                        }

                        if (isRtl && bgVisible) fgView.setTranslationX((bgWidth - Math.abs(translateAmount) > 0 ? bgWidth - Math.abs(translateAmount) : 0));

                        if (fadeViews != null) {
                            for (int viewID : fadeViews) {
                                touchedView.findViewById(viewID).setAlpha(1 - (Math.abs(translateAmount) / bgWidth));
                            }
                        }
                    } else if (deltaX > 0f) {
                        float translateAmount = (deltaX - mSwipingSlop) - bgWidth;

                        if (!isRtl && bgVisible) {
                            fgView.setTranslationX(translateAmount > 0 ? 0 : translateAmount);
                        }

                        if (isRtl && !bgVisible) fgView.setTranslationX(deltaX < bgWidth ? deltaX : bgWidth);

                            // fades all the fadeViews gradually to 0 alpha as dragged
                        if (fadeViews != null) {
                            for (int viewID : fadeViews) {
                                touchedView.findViewById(viewID).setAlpha(1 - (Math.abs(translateAmount) / bgWidth));
                            }
                        }
                    }
                    return true;
                }
                break;
            }
        }
        return false;
    }

    /**
     * Gets coordinates from Activity and closes any
     * swiped rows if touch happens outside the recycler view
     */
    @Override
    public void getTouchCoordinates(MotionEvent ev) {
        int y = (int) ev.getRawY();
        if (bgVisible && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && y < heightOutsideRView) closeVisibleBG(null);
    }

    private boolean shouldIgnoreAction(int touchedPosition) {
        return rView == null ||
                (rView.getAdapter() != null &&
                        ignoredViewTypes.contains(rView.getAdapter().getItemViewType(touchedPosition)));
    }

    private enum Animation {
        OPEN, CLOSE
    }

    ///////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////  Interfaces  /////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////

    public interface OnRowClickListener {
        void onRowClicked(int position);

        void onIndependentViewClicked(int independentViewID, int position);
    }

    public interface OnSwipeOptionsClickListener {
        void onSwipeOptionClicked(int viewID, int position);
    }

    public interface RecyclerTouchListenerHelper {
        void setOnActivityTouchListener(OnActivityTouchListener listener);
    }

    public interface OnSwipeListener {
        void onSwipeOptionsClosed();

        void onSwipeOptionsOpened();
    }
}