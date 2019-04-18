package com.example.administrator.myimageloader.loader;

import android.graphics.Bitmap;
import android.widget.ImageView;

/**
 * Create by SunnyDay on 2019/04/18
 */
public class LoaderResult {
    ImageView imageView;
    String uri;
    public Bitmap bitmap;

    public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
        this.imageView = imageView;
        this.uri = uri;
        this.bitmap = bitmap;
    }
}
