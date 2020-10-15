package com.onlylemi.mapview.library.layer;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Scroller;

import androidx.annotation.NonNull;

import com.onlylemi.mapview.library.LengthUnit;
import com.onlylemi.mapview.library.MapView;
import com.onlylemi.mapview.library.R;

import java.util.ArrayList;

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

    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;

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

    private LengthUnit lengthUnit;

    // grid
    private Paint[] gridPaint = new Paint[GRID_LINES_MAX_LEVELS];
    private ArrayList<GridLine> shownGridLines = new ArrayList<>(GRID_LINES_MAX_LEVELS);
    private Paint gridLabelPaint;
    // GRID_LINE_STEP_CM transformed to scale factor
    private float[] gridLinesMinimalScaleFactor;

    public GridLayer(MapView mapView) {
        super(mapView);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(MotionEvent event) {
        boolean s = mScaleGestureDetector.onTouchEvent(event);
        boolean g = mGestureDetector.onTouchEvent(event);
        return s || g;
    }

    @Override
    public void draw(Canvas canvas, Matrix currentMatrix, float currentZoom, float currentRotateDegrees) {
        canvas.save();
        drawGrid(canvas);
        canvas.restore();
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
