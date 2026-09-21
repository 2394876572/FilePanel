# =====================================================================
#  FilePanel - 重建 docs\screenshots 里的全部截图
#
#  为什么需要它：这些截图会出现在 README 与 docs 里，也就是会被公开看到。
#  真实的工作资料一旦被截进去，撤回来比一开始就别放进去贵得多。所以每一张
#  图都对着 demo\MakeDemo.java 生成的**纯虚构示例文件夹**渲染，一次命令重建。
#
#  用法： powershell -ExecutionPolicy Bypass -File scripts\demo-screenshots.ps1
#
#  为什么是 .ps1 而不是 .cmd：本脚本要写 14 个中文文件名和一句中文搜索词，
#  而 .cmd 必须是纯 ASCII 字节（cmd.exe 按字节偏移跟踪自己在批处理里的位置，
#  掺进非 ASCII 会让它从某一行中间继续执行 —— 实施笔记坑 5，本项目最容易复发
#  的一个坑）。PowerShell 脚本带 UTF-8 BOM 时能正确按 UTF-8 读取，中文才是安全的。
#
#  M3-回收站里确实有它.png 是资源管理器的回收站截图，脚本生成不了，
#  只能人工补（脚本在最后会打印步骤）。
# =====================================================================

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$Java = 'C:\jdk17\bin\java.exe'
$DemoDir = Join-Path $ProjectDir 'demo'
$DemoGenerator = Join-Path $DemoDir 'MakeDemo.java'
$DemoRoot = Join-Path $DemoDir '示例文件夹'
$OutDir = Join-Path $ProjectDir 'docs\screenshots'
$JavaCmd = Join-Path $ScriptDir '_java.cmd'

if (-not (Test-Path $Java)) {
    throw "找不到 JDK 17：$Java（构建脚本硬编码这个路径，见 docs\使用说明-技术人员版.md）"
}
if (-not (Test-Path $DemoGenerator)) {
    throw "找不到示例数据生成器：$DemoGenerator"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# --------------------------------------------------------- 1) 生成示例数据
Write-Host '=== 1/3 生成示例文件夹（纯虚构内容）===' -ForegroundColor Cyan
& $Java '-Dfile.encoding=UTF-8' $DemoGenerator $DemoRoot
if ($LASTEXITCODE -ne 0) {
    throw "MakeDemo 失败，退出码 $LASTEXITCODE"
}
if (-not (Test-Path $DemoRoot)) {
    throw "示例数据没有生成出来：$DemoRoot"
}
Write-Host "    示例文件夹：$DemoRoot"

# ------------------------------------------------------------- 2) 渲染截图
# Flag = 用哪个命令行模式渲染；Query/Theme 只在 --screenshot 时有意义。
$shots = @(
    @{ File = 'M1-主界面.png';              Flag = '--screenshot';       Query = '';                 Theme = 'light' }
    @{ File = 'M2-默认视图.png';            Flag = '--screenshot';       Query = '';                 Theme = 'light' }
    @{ File = 'M2-搜索.png';                Flag = '--screenshot';       Query = 'type:image';       Theme = 'light' }
    @{ File = 'M3-删除确认弹窗.png';        Flag = '--delete-preview';   Query = '';                 Theme = '' }
    @{ File = 'M4-收藏标签与最近使用.png';  Flag = '--screenshot';       Query = 'fav:true';         Theme = 'light' }
    @{ File = 'M5-工具栏与批量重命名.png';  Flag = '--screenshot';       Query = '';                 Theme = 'light' }
    @{ File = 'M6-成品-浅色.png';           Flag = '--screenshot';       Query = '';                 Theme = 'light' }
    @{ File = 'M6-成品-深色.png';           Flag = '--screenshot';       Query = '';                 Theme = 'dark' }
    @{ File = 'M7-内容搜索.png';            Flag = '--screenshot';       Query = 'content:灰度发布'; Theme = 'light' }
    @{ File = 'M8-缩略图与系统图标.png';    Flag = '--screenshot';       Query = 'type:image';       Theme = 'light' }
    @{ File = 'M8-系统图标图集.png';        Flag = '--icon-sheet';       Query = '';                 Theme = '' }
    @{ File = 'M10-搜索范围与语法入口.png'; Flag = '--screenshot';       Query = 'size:1MB';         Theme = 'light' }
    @{ File = 'M10-批量创建面板.png';       Flag = '--create-preview';   Query = '';                 Theme = '' }
    @{ File = '设置-删除阈值.png';          Flag = '--settings-preview'; Query = '';                 Theme = '' }
)

Write-Host '=== 2/3 渲染截图 ===' -ForegroundColor Cyan
$failed = @()
foreach ($shot in $shots) {
    $out = Join-Path $OutDir $shot.File

    # 参数走 PowerShell 的数组传递，不自己拼命令行：_java.cmd 里是 %* 展开，
    # 自己拼字符串时引号容易在 PowerShell -> cmd 这一层被吃掉。
    # 另外搜索词一律避开 > < | 这类 cmd 元字符（大小比较写成 size:1MB 的
    # "大于等于"形式），免得为了引号跟两层解析器较劲。
    $callArgs = @($shot.Flag, $out)
    if ($shot.Query -ne '') { $callArgs += @('--search', $shot.Query) }
    if ($shot.Theme -ne '') { $callArgs += @('--theme', $shot.Theme) }
    $callArgs += @('--root', $DemoRoot)

    Write-Host ("  -> {0}" -f $shot.File)
    & $JavaCmd @callArgs
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) {
        $failed += "$($shot.File)（退出码 $LASTEXITCODE）"
    }
}

if ($failed.Count -gt 0) {
    Write-Host "以下截图渲染失败：" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    throw '截图渲染未全部成功'
}

# ----------------------------------------------------------------- 3) 收尾
Write-Host '=== 3/3 完成 ===' -ForegroundColor Cyan
Get-ChildItem $OutDir -Filter *.png | Sort-Object Name | ForEach-Object {
    Write-Host ("    {0,-34} {1,8:N0} 字节" -f $_.Name, $_.Length)
}
Write-Host ''
Write-Host '还需人工补一张（脚本生成不了）：' -ForegroundColor Yellow
Write-Host '  docs\screenshots\M3-回收站里确实有它.png'
Write-Host "  1) 在示例文件夹里随便选一个文件，用界面删除（走回收站）"
Write-Host "     示例文件夹：$DemoRoot"
Write-Host '  2) 打开 Windows 回收站，确认它在那里，截图存成上面那个文件名'
Write-Host '  3) 补完后再跑一次本脚本也没问题（它不碰这一张）'
