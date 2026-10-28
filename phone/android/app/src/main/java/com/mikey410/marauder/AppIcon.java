package com.mikey410.marauder;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector glyphs rendered at device density; no bitmap assets or icon fonts. */
final class AppIcon extends Drawable {
    private final String name; private final int size; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    AppIcon(String name,int size,int color){this.name=name;this.size=size;paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,paint);}
    @Override public void draw(Canvas canvas){
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);paint.setStyle(Paint.Style.STROKE);
        switch(name){
            case "Nearby":case "Wi-Fi":
                canvas.drawArc(2,4,22,22,222,96,false,paint);canvas.drawArc(6,9,18,21,224,92,false,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,19,1.6f,paint);break;
            case "Bluetooth":
                line(canvas,12,2,12,22);line(canvas,12,2,18,7);line(canvas,18,7,6,17);line(canvas,6,7,18,17);line(canvas,18,17,12,22);break;
            case "Device":case "Hardware":
                canvas.drawRoundRect(5,3,19,21,3,3,paint);line(canvas,9,7,15,7);canvas.drawCircle(12,17,1,paint);break;
            case "Tools":
                for(int x:new int[]{3,14})for(int y:new int[]{3,14})canvas.drawRoundRect(x,y,x+7,y+7,2,2,paint);break;
            case "Activity":case "Monitor":
                line(canvas,2,12,6,12);line(canvas,6,12,9,5);line(canvas,9,5,14,20);line(canvas,14,20,17,10);line(canvas,17,10,22,10);break;
            case "Targets":case "Remote ID":
                canvas.drawCircle(12,12,7,paint);canvas.drawCircle(12,12,2,paint);line(canvas,12,1,12,5);line(canvas,12,19,12,23);line(canvas,1,12,5,12);line(canvas,19,12,23,12);break;
            case "Network":
                canvas.drawRoundRect(8,2,16,8,1,1,paint);line(canvas,12,8,12,13);line(canvas,5,13,19,13);line(canvas,5,13,5,17);line(canvas,19,13,19,17);canvas.drawRoundRect(2,17,8,22,1,1,paint);canvas.drawRoundRect(16,17,22,22,1,1,paint);break;
            default:
                line(canvas,9,3,15,3);line(canvas,10,3,10,10);line(canvas,14,3,14,10);line(canvas,10,10,4,20);line(canvas,14,10,20,20);line(canvas,4,20,20,20);line(canvas,7,15,17,15);
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
