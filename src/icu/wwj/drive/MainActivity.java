package icu.wwj.drive;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页：按 {@link ToolRegistry} 渲染工具卡片，点一张卡片就进对应功能。
 * 首页本身不含业务逻辑，新增功能不需要改这里。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home_main);

        LinearLayout container = (LinearLayout) findViewById(R.id.toolContainer);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (final Tool tool : ToolRegistry.all()) {
            View item = inflater.inflate(R.layout.home_item_tool, container, false);
            ((ImageView) item.findViewById(R.id.icon)).setImageResource(tool.iconRes);
            ((TextView) item.findViewById(R.id.title)).setText(tool.titleRes);
            ((TextView) item.findViewById(R.id.desc)).setText(tool.descRes);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, tool.activity));
                }
            });
            container.addView(item);
        }

        TextView footer = (TextView) findViewById(R.id.txtFooter);
        footer.setText(getString(R.string.home_footer, versionName()));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
