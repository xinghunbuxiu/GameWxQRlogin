package com.willh.wz;

import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Local-only diagnostics for a user-supplied game configuration.
 * This screen does not fabricate or dispatch WeChat SDK callback payloads.
 */
public class GameDiagnosticsActivity extends Activity {
    private EditText nameInput;
    private EditText appIdInput;
    private EditText packageInput;
    private EditText activityInput;
    private TextView resultView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("自定义游戏诊断");
        SharedPreferences prefs = getSharedPreferences("custom_game_diagnostics", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(label("填写游戏信息。AppID 仅做格式检查，不代表官方身份已验证。"));
        nameInput = field(root, "游戏名称", prefs.getString("name", ""));
        appIdInput = field(root, "候选 AppID（wx 开头）", prefs.getString("appId", ""));
        packageInput = field(root, "游戏包名", prefs.getString("packageName", ""));
        activityInput = field(root, "回调 Activity 完整类名", prefs.getString("activityName", ""));

        Button save = new Button(this);
        save.setText("保存配置");
        root.addView(save);
        Button diagnose = new Button(this);
        diagnose.setText("检查安装与 Activity 可达性");
        root.addView(diagnose);
        resultView = label("诊断结果会显示在这里。");
        root.addView(resultView);

        save.setOnClickListener(v -> saveConfig());
        diagnose.setOnClickListener(v -> diagnoseTarget());
        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String hint, String value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setText(value);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(edit);
        return edit;
    }

    private TextView label(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15);
        view.setPadding(0, dp(10), 0, dp(10));
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String value(EditText edit) {
        return edit.getText().toString().trim();
    }

    private void saveConfig() {
        String appId = value(appIdInput);
        if (!appId.isEmpty() && !appId.matches("wx[0-9a-fA-F]{16}")) {
            resultView.setText("保存失败：AppID 格式不像常见的微信开放平台 AppID。格式正确也不代表它属于该游戏。");
            return;
        }
        getSharedPreferences("custom_game_diagnostics", MODE_PRIVATE).edit()
                .putString("name", value(nameInput))
                .putString("appId", appId)
                .putString("packageName", value(packageInput))
                .putString("activityName", value(activityInput))
                .apply();
        resultView.setText("配置已保存在本机。AppID 状态：未验证；不会自动用于授权回调。");
    }

    private void diagnoseTarget() {
        String pkg = value(packageInput);
        String cls = value(activityInput);
        if (pkg.isEmpty()) {
            resultView.setText("请先填写游戏包名。");
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("游戏包名：").append(pkg).append("\n");
        report.append("候选 AppID：")
                .append(value(appIdInput).isEmpty() ? "未填写" : "已填写（真实性未验证）")
                .append("\n");

        try {
            getPackageManager().getPackageInfo(pkg, 0);
            report.append("安装状态：已安装\n");
        } catch (PackageManager.NameNotFoundException e) {
            report.append("安装状态：未安装或系统不可见\n");
        }

        if (cls.isEmpty()) {
            report.append("回调 Activity：未填写");
            resultView.setText(report.toString());
            return;
        }

        report.append("回调 Activity：").append(cls).append("\n");
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            report.append("组件状态：存在\n");
            report.append("导出状态：").append(info.exported ? "已导出" : "未导出").append("\n");
            report.append("访问权限：")
                    .append(info.permission == null ? "未声明 Activity 级权限" : info.permission)
                    .append("\n");
            report.append("说明：组件存在/已导出不等于游戏服务端会接受外部授权结果。");
        } catch (PackageManager.NameNotFoundException e) {
            report.append("组件状态：未找到（类名、包名或系统可见性需复核）");
        } catch (SecurityException e) {
            report.append("组件状态：系统拒绝查询，可能受包可见性或权限限制");
        }
        resultView.setText(report.toString());
    }
}
