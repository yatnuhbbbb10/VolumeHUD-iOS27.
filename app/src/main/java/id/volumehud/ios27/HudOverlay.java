package id.volumehud.ios27;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import android.util.Log;
import de.robv.android.xposed.XposedHelpers;

final class HudOverlay {
    private final Context context;
    private final Object controller;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GlassCapsule view;
    private final WindowManager.LayoutParams lp;
    private boolean expanded = true; // physical press #1 = long; #2 = compact
    private int stream=-1, level=0, min=0, max=15;
    private boolean muted;
    private final Runnable hide = () -> remove();

    HudOverlay(ClassLoader classLoader, Object owner) throws Exception {
        controller = owner;
        Context systemUiContext = null;
        try {
            Object candidate = XposedHelpers.getObjectField(owner, "mContext");
            if (candidate instanceof Context) systemUiContext = (Context) candidate;
        } catch (Throwable ignoredField) { }
        context = systemUiContext;
        if (context == null) throw new IllegalStateException("Transsion controller mContext unavailable");
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) throw new IllegalStateException("SystemUI WindowManager unavailable");
        view = new GlassCapsule(context, this);
        // SystemUI process is privileged; TYPE_VOLUME_OVERLAY is intentional.
        lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            2020, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                 WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                 WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        lp.x = dp(8); lp.y = 0;
        lp.setTitle("VolumeHUD-iOS27");
    }

    void toggleOnPhysicalKey() {
        expanded = !isAttached() ? true : !expanded;
        show();
        view.setExpanded(expanded);
        h.removeCallbacks(hide);
        h.postDelayed(hide, 3200);
    }
    void update(int s,int v,int lo,int hi,boolean m) {
        stream=s; min=lo; max=Math.max(lo+1,hi); level=Math.max(min,Math.min(max,v)); muted=m;
        view.setVolume(level,min,max,muted);
    }
    private void show() {
        try {
            if (!isAttached()) {
                wm.addView(view, lp);
                Log.i("VolumeHUD27", "TYPE_VOLUME_OVERLAY attached");
            } else {
                wm.updateViewLayout(view, lp);
            }
        } catch (Throwable t) {
            // Fail open: do not hide or suppress the native Transsion dialog.
            Log.e("VolumeHUD27", "TYPE_VOLUME_OVERLAY rejected; native dialog remains fallback", t);
        }
    }
    private void remove() {
        try { if(isAttached()) wm.removeView(view); } catch(Throwable ignored){}
    }
    private boolean isAttached() { return view.getParent()!=null; }
    int levelFromY(float y) {
        float usable = Math.max(1, view.getHeight()-dp(24));
        float f = 1f - Math.max(0f,Math.min(1f,(y-dp(12))/usable));
        return min + Math.round(f*(max-min));
    }
    void dragTo(float y) {
        int next=levelFromY(y);
        if(stream>=0 && next!=level) {
            if (VolumeHudEntry.setVolume(controller, stream, next)) {
                level = next;
                view.setVolume(level, min, max, muted);
            }
        }
        h.removeCallbacks(hide);
    }
    void dragFinished() { h.postDelayed(hide,2200); }
    private int dp(float v) { return (int)(v*context.getResources().getDisplayMetrics().density+.5f); }

    private static final class GlassCapsule extends View {
        private final Paint p=new Paint(3);
        private final HudOverlay host;
        private boolean expanded=true, dragging=false;
        private int value=0,lo=0,hi=15;
        private boolean muted=false;
        private float downY;
        GlassCapsule(Context c,HudOverlay h){super(c);host=h;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void setExpanded(boolean b){expanded=b; requestLayout(); invalidate();}
        void setVolume(int v,int l,int h,boolean m){value=v;lo=l;hi=Math.max(l+1,h);muted=m;invalidate();}
        @Override protected void onMeasure(int w,int h) {
            float d=getResources().getDisplayMetrics().density;
            setMeasuredDimension((int)((expanded?42:34)*d),(int)((expanded?190:76)*d));
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float d=getResources().getDisplayMetrics().density;
            float w=getWidth(),hh=getHeight(), inset=2*d;
            RectF r=new RectF(inset,inset,w-inset,hh-inset);
            p.setShader(null);p.setColor(Color.argb(74,245,248,255));p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r,w/2,w/2,p);
            p.setColor(Color.argb(125,255,255,255));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1*d);
            c.drawRoundRect(r,w/2,w/2,p);p.setStyle(Paint.Style.FILL);
            float frac=(float)(value-lo)/(hi-lo);
            float fillH=Math.max(4*d,(hh-4*d)*frac);
            RectF f=new RectF(inset+2*d,hh-inset-2*d-fillH,w-inset-2*d,hh-inset-2*d);
            p.setColor(Color.argb(238,255,255,255));c.drawRoundRect(f,(w-8*d)/2,(w-8*d)/2,p);
            p.setColor(Color.argb(210,255,255,255));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(12*d);
            c.drawText(muted?"×":"♪",w/2,hh-(expanded?12:hh*.18f),p);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN: dragging=true;downY=e.getY();host.dragTo(e.getY());return true;
                case MotionEvent.ACTION_MOVE: if(dragging){host.dragTo(e.getY());return true;}break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if(dragging){dragging=false;host.dragFinished();return true;}break;
            }
            return true;
        }
    }
}
