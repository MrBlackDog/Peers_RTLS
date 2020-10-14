package com.onlylemi.mapview.library.layer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import com.onlylemi.mapview.library.MapView;

import java.util.Random;

public class MotionLayer extends MapBaseLayer {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    int chainlength = 4;
    int range = 100;
    int MAX_SIZE_X;
    int MAX_SIZE_Y;
    float weightCoefficient = 0.1f;
    Point[] chain = new Point[chainlength];
    Point target = new Point();
    Point fly = new Point();
    Point point = new Point();
    Random r = new Random();
    private MySurfaceThread thread;
    private MapView mapView;

    public MotionLayer(MapView mapView) {
        super(mapView);
        this.mapView = mapView;
        for(int i=0;i<chainlength;i++)
        {
            chain[i] = new Point();
        }
        thread = new MySurfaceThread(mapView.getHolder());
        thread.setRunning(true);
        thread.start();
    }

    public class MySurfaceThread extends Thread {

        private SurfaceHolder myThreadSurfaceHolder;
        //private MySurfaceView myThreadSurfaceView;
        private boolean myThreadRun = false;

        public MySurfaceThread(SurfaceHolder surfaceHolder) {
            myThreadSurfaceHolder = surfaceHolder;

            MAX_SIZE_X = (int) mapView.getMapWidth();
            MAX_SIZE_Y = (int) mapView.getMapHeight();
            Log.d("ArTack", String.valueOf(mapView.getMapWidth()));
            Log.d("ArTack", String.valueOf(mapView.getMapHeight()));
            //myThreadSurfaceView = surfaceView;
            target.x = r.nextInt(MAX_SIZE_X);
            target.y = r.nextInt(MAX_SIZE_Y);
        }

        public void setRunning(boolean b) {
            myThreadRun = b;
        }

        @Override
        public void run() {
            // super.run();

            while (myThreadRun) {
                chain[0] = target;
                for (int i = 1; i < chain.length; i++) {
                    point = chain[i];
                    target = chain[i-1];
                    point.x += weightCoefficient*(target.x - point.x);
                    point.y += weightCoefficient*(target.y - point.y);
                    //chain[i].x =  (weightCoefficient * chain[i].x + (1 - weightCoefficient) * chain[i - 1].x);
                    //chain[i].y =  (weightCoefficient * chain[i].y + (1 - weightCoefficient) * chain[i - 1].y);
                }
                target = chain[0];
                //target.x = chain[0].x;
                //target.y = chain[0].y;
                fly.x = chain[chainlength-1].x;
                fly.y = chain[chainlength-1].y;
                float dx = target.x - fly.x;
                float dy = target.y - fly.y;
                if (Math.sqrt(dx * dx + dy * dy) <= range) {
                    while (Math.sqrt(dx * dx + dy * dy) <= range) {
                        target.x = r.nextInt(MAX_SIZE_X);
                        target.y = r.nextInt(MAX_SIZE_Y);
                        dx = target.x - fly.x;
                        dy = target.y - fly.y;
                    }
                }

                mapView.refresh();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                /*Canvas c = null;
                try {
                    c = myThreadSurfaceHolder.lockCanvas(null);
                    chain[0] = target;
                    for (int i = 1; i < chain.length; i++) {
                        point = chain[i];
                        target = chain[i-1];
                        point.x += weightCoefficient*(target.x - point.x);
                        point.y += weightCoefficient*(target.y - point.y);
                        //chain[i].x =  (weightCoefficient * chain[i].x + (1 - weightCoefficient) * chain[i - 1].x);
                        //chain[i].y =  (weightCoefficient * chain[i].y + (1 - weightCoefficient) * chain[i - 1].y);
                    }
                    target = chain[0];
                    //target.x = chain[0].x;
                    //target.y = chain[0].y;
                    fly.x = chain[chainlength-1].x;
                    fly.y = chain[chainlength-1].y;
                    float dx = target.x - fly.x;
                    float dy = target.y - fly.y;
                    if (Math.sqrt(dx * dx + dy * dy) <= range) {
                        while (Math.sqrt(dx * dx + dy * dy) <= range) {
                            target.x = r.nextInt(1000);
                            target.y = r.nextInt(1000);
                            dx = target.x - fly.x;
                            dy = target.y - fly.y;
                        }
                    }
                    synchronized (myThreadSurfaceHolder) {
                        mapView.refresh();
                       // draw(c,mapView.getMatrix(),mapView.getCurrentZoom(),mapView.getCurrentRotateDegrees());
                    }
                } finally {
                    // do this in a finally so that if an exception is thrown
                    // during the above, we don't leave the Surface in an
                    // inconsistent state
                    if (c != null) {
                       myThreadSurfaceHolder.unlockCanvasAndPost(c);
                    }
                }*/
            }
        }
    }

    @Override
    public void onTouch(MotionEvent event) {

    }

    @Override
    public void draw(Canvas canvas, Matrix currentMatrix, float currentZoom, float currentRotateDegrees) {
        canvas.save();
        canvas.setMatrix(currentMatrix);
        //canvas.drawColor(Color.BLACK);
        canvas.drawCircle(fly.x, fly.y, 10, paint);
        canvas.drawCircle(target.x, target.y, 5, paint);
        canvas.restore();
    }
}
