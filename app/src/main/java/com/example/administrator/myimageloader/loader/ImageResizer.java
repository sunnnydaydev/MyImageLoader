package com.example.administrator.myimageloader.loader;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.view.Display;
import android.view.WindowManager;

import java.io.FileDescriptor;

/**
 * Create by SunnyDay on 2019/04/18
 * 图片压缩类，用于完成图片的缩放处理，可以避免oom。
 */
public class ImageResizer {
    private static final String TAG = "ImageResizer";
    private Context context;

    /**
     * 构造方法 用于初始化上下文
     */
    public ImageResizer(Context context) {
        this.context = context;
    }

    /**
     * @param resourceID 资源id
     * @param resources  资源对象
     * @function 缩放资源id类型的图片
     */
    public Bitmap decodeSampledBitmapFromResource(Resources resources, int resourceID) {
        // 不真正解码位图，只获得位图的一些信息(inJustDecodeBounds = true时)
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resourceID, options);
        int weight = options.outWidth;
        int height = options.outHeight;
        // 计算缩放比（手动指定比较快捷，我们这里根据手机尺寸计算,具体实现自己而定）
        options.inSampleSize = caculateScale(weight, height);

        options.inJustDecodeBounds = false;// 真正解码  按照缩放比
        return BitmapFactory.decodeResource(resources,resourceID,options);
    }



    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd){
        return null;
    }



                                                        /**
                                                         * @param weight 图片宽
                                                         * @param height 图片高
                                                         * @return 缩放比例
                                                         * @function 计算缩放比例（根据手机尺寸）
                                                         */
    private int caculateScale(int weight, int height) {
        // 获得手机宽高
        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        int phoneWidth = point.x;
        int phoneHeight = point.y;
        int scale = 1;//默认缩放比为1

        int scaleX = weight / phoneWidth;
        int scaleY = height / phoneHeight;
        if (scaleX >= scaleY && scaleX > scale) {
            scale = scaleX;
        }
        if (scaleY > scaleX && scaleY > scale) {
            scale = scaleY;
        }
        return scale;
    }
}
