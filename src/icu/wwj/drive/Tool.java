package icu.wwj.drive;

import android.app.Activity;

/**
 * 首页上的一个工具入口。
 *
 * <p>全部用资源 id 而不是字面量，方便以后做多语言/换皮肤。
 * 新增功能时在 {@link ToolRegistry} 里 new 一个，不用改首页代码。
 */
public final class Tool {

    /** 稳定标识，仅用于日志/埋点，不参与组件解析。 */
    public final String id;
    public final int titleRes;
    public final int descRes;
    public final int iconRes;
    public final Class<? extends Activity> activity;
    /** 首页排序，小的在前。 */
    public final int order;

    public Tool(String id,
                int titleRes,
                int descRes,
                int iconRes,
                Class<? extends Activity> activity,
                int order) {
        this.id = id;
        this.titleRes = titleRes;
        this.descRes = descRes;
        this.iconRes = iconRes;
        this.activity = activity;
        this.order = order;
    }
}
