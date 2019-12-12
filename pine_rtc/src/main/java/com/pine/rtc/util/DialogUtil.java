package com.pine.rtc.util;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.pine.rtc.R;

/**
 * Created by tanghongfeng on 2017/12/1.
 */

public class DialogUtil {

    public static void popShotScreenDialog(Context context, final Bitmap bitmap) {
        if (bitmap == null) {
            Toast.makeText(context, "无法截屏", Toast.LENGTH_LONG).show();
        }
        final AlertDialog cutDialog = new AlertDialog.Builder(context).create();
        View dialogView = View.inflate(context, R.layout.show_shot_screen_layout, null);
        ImageView imageView = (ImageView) dialogView.findViewById(R.id.show_cut_screen_img);
        cutDialog.setView(dialogView);
        Window window = cutDialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager m = window.getWindowManager();
        Display d = m.getDefaultDisplay(); // 获取屏幕宽、高用
        WindowManager.LayoutParams p = window.getAttributes(); // 获取对话框当前的参数值
        p.height = (int) (d.getHeight() * 0.8); // 高度设置为屏幕的0.6
        p.gravity = Gravity.CENTER;//设置弹出框位置
        window.setAttributes(p);
        window.setWindowAnimations(R.style.dialogWindowAnim);
        cutDialog.setCanceledOnTouchOutside(true);
        imageView.setImageBitmap(bitmap);

        cutDialog.show();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                cutDialog.dismiss();
            }
        }, 3000);
    }
}
