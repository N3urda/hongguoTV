# 像素小电视

用户确认的标识保留奶油色复古电视、右侧双旋钮、扬声器和屏幕里的微笑红果。替换的只是过暗、缩小后不清楚的旧资源，项目身份仍是这台小电视。配套标语为「好故事，大屏看」。本项目为非官方客户端。

`logo-pixel-source.png` 是 imagegen 内置工具根据旧图重绘、经用户确认的原始输出，保持不修改；`logo-pixel.png` 是透明裁边导出。`readme-banner.png` 用于 README，`tv-banner.svg` / `readme-banner.svg` 是包含像素图与字标轮廓的排版文件，不是纯矢量 Logo。

运行 `python3 scripts/generate-brand.py`（需要 ImageMagick 7）生成各密度透明 RGBA APK 图标、144px 界面标记及电视横幅。像素图使用邻近采样，电视横幅按实际密度导出。全版本直接使用透明背景位图图标，仅展示小电视本体，避免有色底板或 `AdaptiveIconDrawable` 强制填充黑底。应用不加载 1280px 原图，不增加图片框架或动画。软件顶部导航使用透明裁边的小电视，不再把带黑色留白的完整图缩成 28dp。

生成提示见 `generation-prompt.txt`。生成方式：内置 imagegen；原稿身份与像素风格由用户确认。

安装名称统一为 `hongguoTV`。安装图标、TV 横幅与应用内标识均从同一透明 RGBA 原稿导出；安装图标仅保留透明背景的小电视本体（占画布宽 81%，兼容圆形与方形图标槽位不裁切），横幅使用 hongguoTV 字标。
