package com.onlylemi.mapview.library.layer;


import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

import androidx.annotation.NonNull;

import com.annimon.stream.function.Supplier;
import com.onlylemi.mapview.library.Animator;
import com.onlylemi.mapview.library.FloorPlan;
import com.onlylemi.mapview.library.LengthUnit;
import com.onlylemi.mapview.library.MapView;
import com.onlylemi.mapview.library.R;
import rx.functions.Action1;

import java.util.ArrayList;
import java.util.List;

import static com.onlylemi.mapview.library.DisplayMetrics.LCD_DIP_SCALING_FACTOR;

public class GridLayer extends MapBaseLayer {

    private static final int INITIAL_ZOOM_RATIO = 200;     // 1 cm on screen is 200 cm / 2m in reality

    private static final int MAX_ZOOM_RATIO = 20;          // 1cm on screen is 20cm in reality
    private static final int MIN_ZOOM_RATIO = 5000;        // 1cm on screen is 5000 cm (50m) in reality
    private static final float ZOOM_FACTOR_ON_DOUBLE_TAP = 2;
    //
    public static final float DEG_TO_RAD = (float) (Math.PI / 180.0f);
    private static final float INCH_TO_CM = 2.54f;
    public static final int CM_IN_METER = 100;
    public static final int CM_IN_KILOMETER = 100000;

    public static final float CM_IN_FEET = 30.48f;
    public static final float CM_IN_YARD = 91.44f;
    public static final int CM_IN_MILE = 160934;
    public static final float CM_IN_INCH = 2.54f;

    private static final int GRID_LINES_MAX_LEVELS = 3;
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // grid scaling configuration
    private static final int SHORTEST_GRID_SQUARE_MM = 5;
    private static final int SHOW_GRID_MARKS_FROM_SCREEN_DISTANCE_CM_MAX = 4;


    public static final int LINE_WIDTH_NODE = 2;
    private static final int RANGING_ANCHOR_LINE_WIDTH = 1;
    private static final int ANCHOR_LABEL_TEXT_SIZE = 12;
    private static final int NODE_SUBINFO_TEXT_SIZE = 10;
    private static final int RANGING_ANCHOR_DISTANCE_LABEL_TEXT_SIZE = 10;
    private static final int TAG_LABEL_TEXT_SIZE = 13;
    private static final int GRID_LABEL_TEXT_SIZE = 10;
    private static final int ANCHOR_MIN_TRIANGLE_SIZE = 2;
    private static final int ANCHOR_MAX_TRIANGLE_SIZE = 7;

    private static final int FADE_OUT_MISSING_TAGS_BEFORE = 7500;
    private static final int FADE_OUT_MISSING_TAGS_AFTER = 5000;
    private static final int FADE_OUT_MISSING_TAGS_DURATION = FADE_OUT_MISSING_TAGS_BEFORE - FADE_OUT_MISSING_TAGS_AFTER;

    public static final int FLOORPLAN_MIN_PIXELS_IN_TEN_METERS = 200;
    public static final int FLOORPLAN_MAX_PIXELS_IN_TEN_METERS = 10000;

    // overall configuration
    private static final int ZOOM_IN_ON_DOUBLE_TAP_DURATION = 500;
    private static final int ZOOM_IN_ANIMATION_DURATION = 2500;
    private static final int NORMALIZATION_DISTANCE_UNIT_FACTOR = 10;
    private static final int CLICK_TOLERANCE_DIP = 30;

    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;

    private Supplier<Boolean> showGridSupplier;
    private Action1<FloorPlan> floorPlanChangedCallback;
    // line widths (relative)
    private static final float[] GRID_LINE_WIDTH = {
            0,        // hairline
            1,
            1
    };

    //
    private static final int[] GRID_LINE_STEP_CM = new int[] {
            10,     // 10cm
            20,
            50,
            100,    // 1m
            200,
            500,
            1000,   // 10m
            2000,
            5000,
            10000,  // 100m
            20000,
            50000,
            100000, // 1km
            200000,
            500000,
            1000000,// 10km
    };
    private static final int[] GRID_LINE_STEP_INCH = new int[] {
            5,          // 5 inches (15 cm)
            12,         // 1 foot - 12 inches
            36,         // 1 yard - 3 feet
            72,         // 2 yards
            180,        // 5 yards
            360,        // 10 yards
            720,        // 20 yards
            1800,       // 50 yards
            3600,       // 100 yards
            7200,
            18000,
            36000,      // 1000 yards
            63360,      // 1 mile (1760 yard)
            126720,     // 2 miles
            316800,     // 5 miles
    };
    // graphics
    private float pixelsPerCm;
    private float maxRangingAnchorLineScaleFactorToPx;
    private float minRangingAnchorLineScaleFactorToPx;
    private float maxAnchorSignGrowToScaleFactorToPx;
    private float minAnchorSignGrowFromScaleFactorToPx;
    private float minAnchorStartFullColorScaleFactorToPx;
    private float maxAnchorFullColorScaleFactorToPx;
    private float maxTagSignGrowToScaleFactorToPx;
    private float minTagSignGrowFromScaleFactorToPx;
    private float clickTolerance;
    private int considerNodesOutOfScreenPx;
    private boolean visibleNodesIndexingScheduled;
    //private FloorPlan floorPlan;
    private boolean floorPlanBound = true;
    //private Action1<FloorPlan> floorPlanChangedCallback;
    private long firstShowSysTime;
    private FloorPlan floorPlan;
    private LengthUnit lengthUnit;

    // grid
    private Paint[] gridPaint = new Paint[GRID_LINES_MAX_LEVELS];
    private ArrayList<GridLine> shownGridLines = new ArrayList<>(GRID_LINES_MAX_LEVELS);
    private Paint gridLabelPaint;
    // GRID_LINE_STEP_CM transformed to scale factor
    private float[] gridLinesMinimalScaleFactor;

    public GridLayer(MapView mapView) {
        super(mapView);
        showGridSupplier = new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return true;
            }
        };

        mGestureDetector = new GestureDetector(mapView.getContext(), new GestureListener());
        mScaleGestureDetector = new ScaleGestureDetector(mapView.getContext(), new OnScaleGestureListener());

        mScroller = new Scroller(mapView.getContext());
        mZoomer = new Animator(mapView.getContext(), new DecelerateInterpolator());
        mFpRotator = new Animator(mapView.getContext(), new AccelerateDecelerateInterpolator());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(MotionEvent event) {
        boolean s = mScaleGestureDetector.onTouchEvent(event);
        boolean g = mGestureDetector.onTouchEvent(event);
        return s || g;
    }

    float[] matrixValues2 = new float[9];
    float currTenMetersInPixels;

    private class OnScaleGestureListener implements ScaleGestureDetector.OnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            //
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();
            if (floorPlanBound) {
                drawMatrix.getValues(matrixAsFloat);
                float finalScale = matrixAsFloat[0] * scaleFactor;
                if (finalScale >= minScaleFactorCmToPx && finalScale <= maxScaleFactorCmToPx) {
                    // do scale
                    drawMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
                    onDrawMatrixChanged();
                    /*if (DEBUG_MATRIX) {
                        log.d("Zoom:drawMatrix = " + drawMatrix);
                    }*/
                    // draw again
                    mapView.refresh();
                    //invalidate();
                    return true;
                }
            } else {
                // we are just mapping this to floorplan properties
                currTenMetersInPixels = currTenMetersInPixels / scaleFactor;
                // we must have at least 20 pixels and at most 1000 pixels in one meter
                if (currTenMetersInPixels >= FLOORPLAN_MIN_PIXELS_IN_TEN_METERS && currTenMetersInPixels <= FLOORPLAN_MAX_PIXELS_IN_TEN_METERS) {
                    drawFloorPlanVirtualMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
                    // let's interpret the values in the matrix
                    if (virtualFloorPlanMatrixToFloorPlanProperties(true)) {
                        // recompute the drawflooplan matrix according to what's saved in the floorplan properties
                        setupBaseFpMatrixFromFpProperties();
                        setupDrawFpMatrix();
                        // notify callback
                        if (floorPlanChangedCallback != null) floorPlanChangedCallback.call(floorPlan);
                    }
                    mapView.refresh();
                    //invalidate();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            // reset caches
            if (floorPlan != null) {
                currTenMetersInPixels = floorPlan.tenMetersInPixels;
            }
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            // do nothing
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (!floorPlanBound) {
                // we are adjusting floorplan, ignore
                return false;
            }
            // determine what was the intention of the click
            float x = e.getX();
            float y = e.getY();
            /*if (DEBUG) {
                log.d("onSingleTapConfirmed: " + "x = [" + x + "], y = [" + y + "]");
            }*/
            //
            /*NetworkNode node = lookupClosest(x, y);
            if (node != null && nodeClickListener != null) {
                nodeClickListener.call(node);
            }*/
            return true;
        }

        // event when double tap occurs
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (!floorPlanBound) {
                // we are adjusting floorplan, ignore
                return false;
            }
            float x = e.getX();
            float y = e.getY();
            // start zoom in animation
            /*if (DEBUG) {
                log.d("DblTap at: (" + x + "," + y + ")");
            }*/
            if (scaleFactorCmToPx < maxScaleFactorCmToPx * 0.9) {
                setupZoomAnimation(x, y, ZOOM_FACTOR_ON_DOUBLE_TAP, ZOOM_IN_ON_DOUBLE_TAP_DURATION);
                ignoreNextAbort = true;

                mapView.refresh();
                //invalidate();
                return true;
            }
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (floorPlanBound) {
                drawMatrix.postTranslate(-dx, -dy);
                /*if (DEBUG_MATRIX) {
                    log.d("Drag:drawMatrix = " + drawMatrix);
                }*/
                onDrawMatrixTranslationChanged();
            } else {
                drawFloorPlanVirtualMatrix.postTranslate(-dx, -dy);
                // recompute the shift to floorplan properties
                if (virtualFloorPlanMatrixToFloorPlanProperties(false)) {
                    // recompute the drawfloorplan matrix according to what's saved in the floorplan properties
                    setupBaseFpMatrixFromFpProperties();
                    setupDrawFpMatrix();
                    // notify callback
                    if (floorPlanChangedCallback != null) floorPlanChangedCallback.call(floorPlan);
                }
            }
            mapView.refresh();
            //invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            /*if (DEBUG) {
                log.d("xVelocity = " + velocityX);
                log.d("yVelocity = " + velocityY);
            }*/
            if (floorPlanBound) {
                // remember how the matrix looked like
                flingStartMatrix.set(drawMatrix);
                // start the fling gesture
                mScroller.fling(
                        0, 0,
                        (int) velocityX, (int) velocityY,
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MAX_VALUE
                );
                // draw again
                mapView.refresh();
                //invalidate();
            } // else: do not allow fling when adjusting floorplan
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            abortOngoingAnimations();
            return super.onDown(e);
        }
    }

    /**
     * Convert virtual floorplan draw matrix values to floorplan properties.
     * @param alsoScale whether to adjust also tenMetersInPixels value (optimization)
     * @return true if there was any change in floorPlan properties
     */
    private boolean virtualFloorPlanMatrixToFloorPlanProperties(boolean alsoScale) {
        // determine the shift from drawFloorPlanMatrixVirtual
        drawFloorPlanVirtualMatrix.getValues(matrixValues2);
        // check the values
        /*if (DEBUG) {
            // make precondition checks
            Preconditions.checkState(matrixValues2[Matrix.MSCALE_X] == matrixValues2[Matrix.MSCALE_Y],
                    "scales are different: " + matrixValues2[Matrix.MSCALE_X] + " != " + matrixValues2[Matrix.MSCALE_Y]);
            Preconditions.checkState(matrixValues2[3] == 0);
            Preconditions.checkState(matrixValues2[1] == 0);
        }*/
        float fpPxToCanvasPxScale = matrixValues2[Matrix.MSCALE_X];
        // compute the pure floorplan screen pixel shift (eliminate the shift caused by grid scroll)
        float floorPlanScreenPixelShiftX = matrixValues2[Matrix.MTRANS_X] - xPxOffset;
        float floorPlanScreenPixelShiftY = matrixValues2[Matrix.MTRANS_Y] - yPxOffset;
        // transform the screen shift to floorplan pixel shift
        float floorPlanCenterX = - floorPlanScreenPixelShiftX / fpPxToCanvasPxScale;
        float floorPlanCenterY = - floorPlanScreenPixelShiftY / fpPxToCanvasPxScale;
        // save the shift
        int oldPxCenterX = floorPlan.pxCenterX;
        int oldPxCenterY = floorPlan.pxCenterY;
        floorPlan.pxCenterX = Math.round(floorPlanCenterX);
        floorPlan.pxCenterY = Math.round(floorPlanCenterY);
        boolean change = oldPxCenterX != floorPlan.pxCenterX || oldPxCenterY != floorPlan.pxCenterY;
        if (alsoScale) {
            // save the scale
            currTenMetersInPixels = 1000 * scaleFactorCmToPx / fpPxToCanvasPxScale;
            // propagate the values to floorplan properties
            int oldTenMetersInPixels = floorPlan.tenMetersInPixels;
            floorPlan.tenMetersInPixels = (int) (currTenMetersInPixels + 0.5);
            change = change || (oldTenMetersInPixels != floorPlan.tenMetersInPixels);
        }
        return change;
    }

    private boolean ignoreNextAbort = false;

    private void abortOngoingAnimations() {
        /*if (Constants.DEBUG) {
            log.d("abortOngoingAnimations");
        }*/
        if (ignoreNextAbort) {
            ignoreNextAbort = false;
            return;
        }
        if (!mZoomer.isFinished()) {
            mZoomer.abortAnimation();
        }
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
    }

    private void setupZoomAnimation(float x, float y, float zoomFactor, int zoomInDuration) {
        float targetScaleFactorToPx = Math.min(Math.max(scaleFactorCmToPx * zoomFactor, minScaleFactorCmToPx), maxScaleFactorCmToPx);
        float targetZoomFactor = targetScaleFactorToPx / scaleFactorCmToPx;
        zoomFocalPoint.set(x, y);
        zoomStartMatrix.set(drawMatrix);
        /*if (DEBUG) {
            log.d("starting zoom animation, targetZoomFactor = " + targetZoomFactor + ", targetScaleFactorToPx = " + targetScaleFactorToPx);
        }*/
        mZoomer.startAnimation(1, targetZoomFactor - 1, zoomInDuration);
    }

    private void setupDrawFpMatrix() {
        // floor plan matrix - move the center to the same center as the grid
        drawFloorPlanMatrix.set(baseFloorPlanMatrix);
        drawFloorPlanMatrix.postTranslate(xPxOffset, yPxOffset);
        //
        /*if (DEBUG_MATRIX) {
            log.d("xPxOffset = " + xPxOffset);
            log.d("yPxOffset = " + yPxOffset);
        }*/
    }

    @Override
    public void draw(Canvas canvas, Matrix currentMatrix, float currentZoom, float currentRotateDegrees) {
        canvas.save();
        boolean redraw = computeAnimations();
        //
        /*if (floorPlan != null) {
            drawFloorPlan(canvas);
        }*/
        // draw the grid
        if (showGridSupplier.get()) drawGrid(canvas);
        // draw the nodes, check if they are sorted
        //drawNodes(canvas);
        if (firstShowSysTime + FADE_OUT_MISSING_TAGS_BEFORE > SystemClock.uptimeMillis()) {
            redraw = true;
        }
        if (redraw) {
            canvas.restore();
        }

    }

    float matrixAsFloat[] = new float[9];

    private boolean computeAnimations() {
        boolean redraw = false;
        if (!mScroller.isFinished()) {
            // compute the next scroll offset
            mScroller.computeScrollOffset();
            // set proper drawmatrix
            drawMatrix.set(flingStartMatrix);
            // get the offset from scroller
            drawMatrix.postTranslate(mScroller.getCurrX(), mScroller.getCurrY());
            onDrawMatrixTranslationChanged();
            redraw = true;
        }
        if (!mZoomer.isFinished()) {
            drawMatrix.set(zoomStartMatrix);
            mZoomer.computeValue();
            float currValue = mZoomer.getCurrValue();
            /*if (DEBUG) {
                log.d("AUTO-ZOOM: currValue = " + currValue);
            }*/
            drawMatrix.postScale(currValue, currValue, zoomFocalPoint.x, zoomFocalPoint.y);
            onDrawMatrixChanged();
            redraw = true;
        }
        if (!mFpRotator.isFinished()) {
            mFpRotator.computeValue();
            redraw = true;
        }
        return redraw;
    }

    private void onDrawMatrixChanged() {
        drawMatrix.getValues(matrixAsFloat);
        // extract the scale
        scaleFactorCmToPx = matrixAsFloat[0];
        // fill shown grid lines
        fillGridLinesForScale(shownGridLines, scaleFactorCmToPx);
        /*if (DEBUG) {
            log.d("scaleFactorCmToPx = " + scaleFactorCmToPx);
            logGridLinesForCurrentScale();
        }*/
        /////////////////////////////////////////////////////////////////////////////////////////
        // determine how big are the anchor triangles
        /////////////////////////////////////////////////////////////////////////////////////////
        // determine how big are tag circles
        float tagGrowFactor;
        if (scaleFactorCmToPx >= maxTagSignGrowToScaleFactorToPx) {
            tagGrowFactor = 1;
        } else if (scaleFactorCmToPx <= minTagSignGrowFromScaleFactorToPx) {
            tagGrowFactor = 0;
        } else {
            float diff = scaleFactorCmToPx - minTagSignGrowFromScaleFactorToPx;
            tagGrowFactor = diff / (maxTagSignGrowToScaleFactorToPx - minTagSignGrowFromScaleFactorToPx);
        }
        //
        /*if (Constants.DEBUG) {
            // have 1% tolerance
            Preconditions.checkState(scaleFactorCmToPx <= maxScaleFactorCmToPx * 1.01,
                    "scaleFactorCmToPx = " + scaleFactorCmToPx + ", max = " + maxScaleFactorCmToPx);
            Preconditions.checkState(scaleFactorCmToPx >= minScaleFactorCmToPx * 0.99,
                    "scaleFacotrToPx = " + scaleFactorCmToPx + ", min = " + minScaleFactorCmToPx);
        }*/
        /////////////////////////////////////////////////////////////////////////////////////////
        // adjust floor plan matrix accordingly
        // center the floor plan
    }
    private void setupBaseFpMatrixFromFpProperties() {
        baseFloorPlanMatrix.reset();
        baseFloorPlanMatrix.postTranslate(-floorPlan.pxCenterX, -floorPlan.pxCenterY);
        // now apply the proper scaling
        // how many pixels is 10 meters in the current grid scale?
        float gridTenMetersInPixels = 1000 * scaleFactorCmToPx;
        // we need to shrink/stretch floorPlan.tenMetersInPixels to gridTenMetersInPixels
        float scale = gridTenMetersInPixels / floorPlan.tenMetersInPixels;
        baseFloorPlanMatrix.postScale(scale, scale);
    }

    private float getScaleFactorTransformed(float maxScaleFactor, float minScaleFactor) {
        float anchorGrowFactor;
        if (scaleFactorCmToPx >= maxScaleFactor) {
            anchorGrowFactor = 1;
        } else if (scaleFactorCmToPx <= minScaleFactor) {
            anchorGrowFactor = 0;
        } else {
            float diff = scaleFactorCmToPx - minScaleFactor;
            anchorGrowFactor = diff / (maxScaleFactor - minScaleFactor);
        }
        return anchorGrowFactor;
    }

    private void fillGridLinesForScale(List<GridLine> gridLines, float scale) {
        // find the shown grid lines
        int lineIdx = 0;
        int i = 0;
        // optimization: find if the current set of shown lines is OK
        if (gridLines.size() > 0) {
            GridLine firstGridLine = gridLines.get(0);
            int idx = firstGridLine.idx;
            if (gridLinesMinimalScaleFactor[idx] <= scaleFactorCmToPx) {
                // condition fulfilled, check if the previous line fulfills it too
                if (idx == 0 || gridLinesMinimalScaleFactor[idx - 1] > scaleFactorCmToPx) {
                    // either there is no previous line or the previous line does not fulfill the condition
                    i = -1;
                } // else: check the whole line set again - from the beginning
            } else {
                // the algorithm may start from a different start idx
                i = firstGridLine.idx + 1;
            }
        }
        if (i != -1) {
            // compute
            Integer firstGridLineStep = null;
            Integer lastGridLineStep = null;
            for (; i < gridLinesMinimalScaleFactor.length; i++) {
                if (gridLinesMinimalScaleFactor[i] <= scaleFactorCmToPx) {
                    GridLine line;
                    int step;
                    if (lengthUnit == LengthUnit.METRIC) {
                        step = GRID_LINE_STEP_CM[i];
                    } else {
                        step = GRID_LINE_STEP_INCH[i];
                    }
                    if (firstGridLineStep != null) {
                        // check that this gridline step is multiplication of the finest gridline step
                        if ((step % firstGridLineStep) != 0) {
                            // skip to the next gridline, it wouldn't be visually pleasant
                            continue;
                        }
                        // check if this gridline step is 'far enough' from the previous one
                        if (lastGridLineStep * 3 >= step) {
                            // skip this one
                            continue;
                        }
                    } else {
                        firstGridLineStep = step;
                    }
                    if (gridLines.size() == lineIdx) {
                        // add one more line
                        line = new GridLine();
                        gridLines.add(line);
                    } else {
                        line = gridLines.get(lineIdx);
                    }
                    // set up the parameters
                    if (line.idx != i) {
                        line.idx = i;
                        line.stepInNaturalUnits = step;
                    }
                    lastGridLineStep = line.stepInNaturalUnits;
                    // check if we are done
                    if (++lineIdx >= GRID_LINES_MAX_LEVELS) {
                        break;
                    }
                }
            }
        }
        // recompute stepInPx
        for (GridLine line : shownGridLines) {
            float stepInCm = line.stepInNaturalUnits;
            if (lengthUnit == LengthUnit.IMPERIAL) {
                // inch -> cm
                stepInCm *= CM_IN_INCH;
            }
            line.stepInPx = scale * stepInCm;
        }
    }

    private void onDrawMatrixTranslationChanged() {
        drawMatrix.getValues(matrixAsFloat);
        onDrawMatrixTranslationChangedArrayReady();
    }
    private void onDrawMatrixTranslationChangedArrayReady() {
        // extract the translation values
        xPxOffset = matrixAsFloat[2];
        yPxOffset = matrixAsFloat[5];
        // shift the matrix
        //if (floorPlan != null) setupDrawFpMatrix();
    }

    private class GridLine {
        int idx;
        int stepInNaturalUnits;     // this is either cm or in
        float stepInPx;

        @Override
        public String toString() {
            return "GridLine{" +
                    "idx=" + idx +
                    ", stepInNaturalUnits=" + stepInNaturalUnits +
                    ", stepInPx=" + stepInPx +
                    '}';
        }
    }

    // floorplan
    private Matrix baseFloorPlanMatrix = new Matrix();
    private Matrix drawFloorPlanMatrix = new Matrix();
    private Matrix drawFloorPlanVirtualMatrix = new Matrix();

    // matrix
    private Matrix drawMatrix;
    private Matrix flingStartMatrix = new Matrix();
    private Matrix zoomStartMatrix = new Matrix();
    private PointF zoomFocalPoint = new PointF();

    // restore state
    private PointF injectedFocalPoint = null;
    private Float injectedScale;
    private Float extraAnimatedZoom = 1f;

    // scaling, offsets, fling, scrolling
    private int pxWidth;
    private int pxHeight;
    private float scaleFactorCmToPx;
    private float maxScaleFactorCmToPx;
    private float minScaleFactorCmToPx;
    // minimal step in pixel for which we start drawing marks
    private float minStepInPxGridMark;
    private float xPxOffset;
    private float yPxOffset;
    private Scroller mScroller;
    protected Animator mZoomer;
    protected Animator mFpRotator;

    float[] fIn = new float[2];
    float[] fOut = new float[2];

    private enum DrawLabels {
        NO, YES, EVERY_OTHER
    }

    
    private void drawGrid(Canvas canvas) {
        int i = 0;
        for (GridLine gridLine : shownGridLines) {
            // determine whether we should draw labels
            DrawLabels drawLabels = DrawLabels.NO;
            // check if it is time to draw labels
            if (gridLine.stepInPx >= minStepInPxGridMark) {
                drawLabels = DrawLabels.YES;
            } else if (gridLine.stepInPx * 2 >= minStepInPxGridMark){
                // check the next gridline, if the marks are close to each other enough
                drawLabels = DrawLabels.EVERY_OTHER;
            }
            drawSingleGrid(canvas,
                    gridPaint[i++],
                    gridLine.stepInPx,
                    gridLine.stepInNaturalUnits,
                    drawLabels);
            if (drawLabels != DrawLabels.NO) {
                // once we have drawn the labels, we will not draw any other grid
                break;
            }
            mapView.refresh();
        }
    }

    private void drawSingleGrid(Canvas canvas, Paint paint, float gridLineStepInPx, int gridLineStepInNaturalUnits, DrawLabels drawLabels) {
        // how far we must go to not miss a single line
        int maxHorizontalLineIdx = Math.round(yPxOffset / gridLineStepInPx);
        int minVerticalLineIdx = -Math.round(xPxOffset / gridLineStepInPx);
        float gridLineStepInCm = gridLineStepInNaturalUnits;
        if (lengthUnit == LengthUnit.IMPERIAL) {
            gridLineStepInCm *= CM_IN_INCH;
        }

        int realStartX = Math.round(minVerticalLineIdx * gridLineStepInCm);
        int realX = realStartX;
        int realY = Math.round(maxHorizontalLineIdx * gridLineStepInCm);

        fIn[0] = realX;
        fIn[1] = realY;
        // transform the x,y coordinates
        drawMatrix.mapPoints(fOut, fIn);
        float pxPosX = fOut[0];
        float pxPosY = fOut[1];

        // draw horizontal lines
        int i = 0;
        float y;
        do {
            y = pxPosY + (i++ * gridLineStepInPx);
            canvas.drawLine(0, y, pxWidth, y, paint);
        } while (y <= pxHeight);

        // draw vertical lines
        i = 0;
        float x;
        do {
            x = pxPosX + (i++ * gridLineStepInPx);
            canvas.drawLine(x, 0, x, pxHeight, paint);
        } while (x <= pxWidth);

        float labelStepInPx = gridLineStepInPx;
        float labelRealStep = gridLineStepInCm;

        if (drawLabels != DrawLabels.NO) {
            // draw labels
            pxPosY = fOut[1];
            float pxStartX = fOut[0];
            if (drawLabels == DrawLabels.EVERY_OTHER) {
                labelRealStep *= 2;
                labelStepInPx *= 2;
                int xFitsIn = Math.round(realStartX / gridLineStepInCm);
                if (xFitsIn % 2 != 0) {
                    realStartX += gridLineStepInCm;
                    pxStartX += gridLineStepInPx;
                }
                int yFitsIn = Math.round(realY / gridLineStepInCm);
                if (yFitsIn % 2 != 0) {
                    realY -= gridLineStepInCm;
                    pxPosY += gridLineStepInPx;
                }
            }
            do {
                pxPosX = pxStartX;
                realX = realStartX;
                do {
                    drawGridLabel(canvas, realX, realY, pxPosX, pxPosY);
                    pxPosX += labelStepInPx;
                    realX += labelRealStep;
                } while (pxPosX <= pxWidth);
                pxPosY += labelStepInPx;
                realY -= labelRealStep;
            } while (pxPosY <= pxHeight);
        }
    }

    private void drawGridLabel(Canvas canvas, int realPosX, int realPosY, float pxPosX, float pxPosY) {
        gridLabelPaint.setTextAlign(Paint.Align.RIGHT);
        //
        String x = getHumanReadableDistance(realPosX);
        String y = getHumanReadableDistance(realPosY);
        // draw label
        canvas.drawText(x, pxPosX - 0.4f * GRID_LABEL_TEXT_SIZE * LCD_DIP_SCALING_FACTOR, pxPosY - 0.5f * GRID_LABEL_TEXT_SIZE * LCD_DIP_SCALING_FACTOR, gridLabelPaint);
        gridLabelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(y, pxPosX + 0.4f * GRID_LABEL_TEXT_SIZE * LCD_DIP_SCALING_FACTOR, pxPosY + 1.2f * GRID_LABEL_TEXT_SIZE * LCD_DIP_SCALING_FACTOR, gridLabelPaint);
    }

    private String getHumanReadableDistance(int realDistance) {
        if (lengthUnit == LengthUnit.METRIC) {
            return getHumanReadableMetricDistance(realDistance);
        } else {
            return getHumanReadableImperialDistance(realDistance);
        }
    }

    private String getHumanReadableImperialDistance(int realDistance) {
        if (realDistance == 0) {

            return "0 " +  mapView.getContext().getString(R.string.unit_yd);
        }
        // more 'analysis' needed
        boolean notLessThanYard = Math.abs(realDistance) >= CM_IN_YARD;
        boolean lessThanMile = Math.abs(realDistance) < CM_IN_MILE;
        if (notLessThanYard && lessThanMile) {
            // distance between 1yd and 1mi
            return String.valueOf(Math.round(realDistance / CM_IN_YARD)) + " " + mapView.getContext().getString(R.string.unit_yd);
        } else if (!notLessThanYard) {
            // less then 1yd
            return String.valueOf(Math.round(realDistance / CM_IN_FEET)) + " " + mapView.getContext().getString(R.string.unit_ft);
        } else {
            // more than a mile
            return String.valueOf(Math.round(realDistance / CM_IN_MILE)) + " " + mapView.getContext().getString(R.string.unit_mi);
        }
    }

    @NonNull
    private String getHumanReadableMetricDistance(int realDistance) {
        if (realDistance == 0) {
            return "0 " + mapView.getContext().getString(R.string.unit_m);
        }
        // more 'analysis' needed
        boolean notLessThanMeter = Math.abs(realDistance) >= CM_IN_METER;
        boolean lessThanKilometer = Math.abs(realDistance) < CM_IN_KILOMETER;
        if (notLessThanMeter && lessThanKilometer) {
            // distance between 1m and 1km
            return String.valueOf(realDistance / CM_IN_METER) + " " + mapView.getContext().getString(R.string.unit_m);
        } else if (!notLessThanMeter) {
            // less then one meter
            return realDistance + " " + mapView.getContext().getString(R.string.unit_cm);
        } else {
            // more than kilometer
            return String.valueOf(realDistance / CM_IN_KILOMETER) + " " + mapView.getContext().getString(R.string.unit_km);
        }
    }
}
