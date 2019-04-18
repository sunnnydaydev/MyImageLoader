package com.example.administrator.myimageloader.loader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.example.administrator.myimageloader.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Create by SunnyDay on 2019/04/18
 * 仿写实现ImageLoader的大概功能：三级缓存、图片压缩、同步异步加载。
 */
public class MyImageLoader {
    private static final int TAG_KEY_URI = R.id.imageloader_uri;//ids.xml.xml   文件中定义
    private static final String TAG = "ImageLoader";
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private Context mContext;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;// 磁盘缓存大小50M
    private boolean mIsDiskLruCacheCreated = false;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache = null;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int MESSAGE_POST_RESULT = 1;
    private ImageResizer mImageResizer;

    private static final int CPU_COUNT = Runtime.getRuntime()
            .availableProcessors();// cup count
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1; //核心池
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;//最大池
    private static final long KEEP_ALIVE = 10L;// 保活时间

    // 线程工厂
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    // 自定义线程池
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory);

    // Handler
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            //url发生给变就不设置图片 没改变时在设置
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.w(TAG, "set image bitmap,but url has changed, ignored!");
            }
        }
    };

    /**
     * @param context 上下文
     * @function 调用构造函数时完成主要完成磁盘缓存、内存缓存的初始化工作
     */
    private MyImageLoader(Context context) {
        mContext = context.getApplicationContext();

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);// 当前进程最大内存（kb）
        int cacheSize = maxMemory / 8;//缓存的最大值，当前进程可用内存的1/8

        // 内存缓存初始化
        //位图的内存大小计算（参看Bitmap的api）
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;//位图的内存大小计算（参看Bitmap的api）
            }
        };

        // 磁盘缓存的初始化工作
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        //当指定目录的可用空间大于指定缓存的最大值时开始初始化
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;//标记 此处表示磁盘缓存建立
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * @function 提供MyImageLoader对象
     */
    public static MyImageLoader build(Context context) {
        return new MyImageLoader(context);
    }

    /**
     * @param key
     * @param bitmap
     * @function 添加到内存缓存
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * @param key
     * @function 获取内存缓存
     */
    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }


    /**
     * @param url 图片url
     * @function 网络下载图片缓存到磁盘缓存中
     */
    private Bitmap loadBitmapFromHttp(String url) throws IOException {
        //禁止再主线程访问网络
        //Looper.myLooper() == Looper.getMainLooper() 如何判断线程是主线程 的面试题
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI Thread.");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        String key = hashKeyFormUrl(url);// hash url
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);// 获得editor对象
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            // 缓存到磁盘中
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }

        return loadBitmapFromDiskCache(url);
    }

    /**
     * @function 获得磁盘缓存
     */
    private Bitmap loadBitmapFromDiskCache(String url) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "load bitmap from UI Thread, it's not recommended!");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
        if (snapShot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapShot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            mImageResizer = new ImageResizer(mContext);
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor);
            if (bitmap != null) {
                //读取磁盘缓存时 往内存中放一份
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }

    /**
     * @param url
     * @function 获得磁盘缓存
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    /**
     * @param uri
     * @function 同步加载图片
     */
    public Bitmap loadBitmap(String uri) {
        // 1、首先从内存中读取
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            Log.d(TAG, "loadBitmapFromMemCache,url:" + uri);
            return bitmap;
        }
        try {
            // 2、内存中没有 从磁盘中加载
            bitmap = loadBitmapFromDiskCache(uri);
            if (bitmap != null) {
                Log.d(TAG, "loadBitmapFromDisk,url:" + uri);
                return bitmap;
            }
            // 3、磁盘没有 从网络加载 并下载到磁盘中
            bitmap = loadBitmapFromHttp(uri);
            Log.d(TAG, "loadBitmapFromHttp,url:" + uri);
        } catch (IOException e) {
            e.printStackTrace();
        }


        if (bitmap == null && !mIsDiskLruCacheCreated) {
            Log.w(TAG, "encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }

        return bitmap;
    }

    /**
     * @function 异步加载图片
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        imageView.setTag(TAG_KEY_URI, uri);
        // 1、首先从内存中加载
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {

            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);

                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }
//---------------------------------------------------工具类----------------------------------------------

    /**
     * @param context    上下文
     * @param uniqueName 文件名
     * @function 获取缓存路径
     * <p>
     * 思路：有内存卡时缓存到 他的默认目录（cache）
     * 没有时缓存到本地缓存默认路径
     */
    private File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            //  getExternalCacheDir()默认路径： /sdcard/Android/data/<application package>/cache
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            // getCacheDir 手机的默认cache 目录
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * @param path 文件目录路径
     * @function 获得指定磁盘目录的可用空间
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
    }

    /**
     * @param key
     * @function 吧url进行MD5加密
     */
    public String hashKeyFormUrl(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    /**
     * @function 实现MD5的功能（字符为0-F）
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 文件下载功能
     *
     * @param urlString    url
     * @param outputStream 输出流
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), 8 * 1024);
            out = new BufferedOutputStream(outputStream, 8 * 1024);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * @function 从网络获得位图
     */
    private Bitmap downloadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),
                    IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmap: " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }
}
