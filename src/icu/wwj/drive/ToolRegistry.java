package icu.wwj.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import icu.wwj.drive.dashcam.DashcamActivity;
import icu.wwj.drive.stitch.StitchHomeActivity;

/**
 * 工具注册表：首页只认这张表，不认具体功能。
 *
 * <p>新增一个独立功能的完整步骤：
 * <ol>
 *   <li>建包 {@code icu.wwj.drive.<feature>}，写自己的 Activity/Service；</li>
 *   <li>AndroidManifest 里声明组件，authority / action 等字符串也统一用
 *       {@code icu.wwj.drive.<feature>.*} 前缀；</li>
 *   <li>这里 {@code TOOLS.add(...)} 加一行（图标、文案、排序），首页自动出现入口；</li>
 *   <li>存储按 {@code Movies/<Feature>} 放公共目录，需要跨功能读素材时走 MediaStore。</li>
 * </ol>
 */
public final class ToolRegistry {

    private ToolRegistry() {
    }

    private static final List<Tool> TOOLS = new ArrayList<>();

    static {
        TOOLS.add(new Tool(
                "dashcam",
                R.string.tool_dashcam_title,
                R.string.tool_dashcam_desc,
                R.mipmap.ic_launcher,
                DashcamActivity.class,
                10));

        TOOLS.add(new Tool(
                "stitch",
                R.string.tool_stitch_title,
                R.string.tool_stitch_desc,
                R.drawable.ic_stitch,
                StitchHomeActivity.class,
                20));
    }

    /** 按 order 排好序的只读副本。 */
    public static List<Tool> all() {
        List<Tool> list = new ArrayList<>(TOOLS);
        Collections.sort(list, new Comparator<Tool>() {
            @Override
            public int compare(Tool a, Tool b) {
                return Integer.compare(a.order, b.order);
            }
        });
        return Collections.unmodifiableList(list);
    }
}
