<div align="center">
  <img src="assets/XiaoCan_LSPosed_logo_512.png" width="128" alt="XiaoCanPurify Logo"/>

# 小蚕净化 (XiaoCanPurify)

针对小蚕惠生活 (`com.realtech.xiaocan`) 的现代化 Xposed / LSPosed API 102 净化模块。
</div>

## 功能特性

1. **广告过滤 (Ads Filtering)**
   - **冷启动开屏广告**：自动秒跳 `SplashActivity` / `BaseAdActivity` 进入主界面。
   - **切后台热启动开屏**：拦截 `SplashAdUtils.initAd` 与 `SplashAdPlacementManager.refreshAsync`，彻底阻断回到前台时的开屏广告。
   - **全屏/激励/插屏广告**：拦截并自动关闭穿山甲 (CSJ/Pangle)、优量汇 (GDT)、快手 (Kwad)、Sigmob、Baidu、ToBid/WindMill、Beizi 等第三方 SDK 广告 Activity。
   - **信息流与卡片广告**：屏蔽 `BaseBqtContainerView` 与 `BqtAdContainerView`，置为 `View.GONE` 且零宽高；桩化 `AdServiceFactoryImpl` 广告流。
   - **Flutter 广告插件**：拦截 `AmSdkPlugin`、`AmInterstitialAdHandler` 及 `WindmillAdPlugin` 的 MethodChannel 广告分发。
   - **静默任务引擎**：阻断 `SilentTaskEngine.start`，禁用后台隐蔽任务加载与上报。
   - **首页广告元素**：隐藏 `HomeRedPackRainFabView` (浮动红包)、`HomeNewUserFabView` (新手引导)、顶部横幅 Banner、搜索右侧 Banner (`HomeAppBarLayoutView`)。

2. **去除各类弹窗 (Popups Removal)**
   - **首页营销活动弹窗**：自动拦截 `SharerHomePopupDialog`、`FirstOrderFullAmountAgencyDialog` (首单全返)。
   - **红包与商城弹窗**：拦截 `DouyinMallBonusDialog`、生日/周年庆蛋糕弹窗、分享奖励弹窗 (`OpenSchoolshareDialog`, `AnnualReportShareDialog` 等)。
   - **应用升级弹窗**：拦截 `AppUpdateServiceImpl.checkUpdate` 与 `MainViewModel.checkUpdate`，彻底杜绝强更/检查更新弹窗。
   - **元宝与通知弹窗**：拦截 `MainViewModel.checkSyceeUpdateDialog`、`checkSchoolStarts` 与 `takeGiftReq`。
   - **全局弹窗队列**：净化 `DialogUtils.addDialog` 与 Kuikly `GlobalDialogQueue.show`，阻断营销类弹窗入队。

3. **精简底部导航栏 (Bottom Navigation Simplification)**
   - 可通过开关分别移除「会员」(VIP) 与「福利/赚钱」(Task/Benefit) 两个冗余标签，支持任意组合。
   - 只移除底部导航项，ViewPager 页面保持宿主原始 5 页完整：被隐藏的福利/会员页仍可通过「我的-邀新赚钱」等功能入口正常跳转。
   - 导航点击通过位置映射（导航子项位置 -> 原始页面索引）切换页面，不会出现点击「我的」却打开 SVIP 页的错位。
   - 同时隐藏时底部仅保留三个核心 Tab：
     1. **首页** (`HOME`)
     2. **订单** (`ORDER`)
     3. **我** (`USER`)
   - 保留原有的到店/订单交互以及「我的」页面切换与未读提醒逻辑。

4. **功能开关 (Feature Toggles)**
     - **底部导航栏**：会员 Tab、福利/赚钱 Tab、抖音补贴营销占位；
     - **首页**：顶部红包 Banner/活动背景、搜索右侧「抖音补贴」、红包快捷入口行、0元探店/到店霸王餐卡片、「开心收下」订单横条、浮动红包/新手引导、底部悬浮红包、下拉二楼；
     - **「我」页面**：挑战赛/免费抽奖活动行（**逐子项开关**：挑战赛、免费抽奖、对接暗号、红包雨、有奖消消乐、免单资格卡，各自独立控制；全部关闭时整行自动隐藏）、霸王餐入驻/0元探店入驻横条、小蚕会员 SVIP 轮播卡、电商/生活/出行特惠网格、公告横幅、客服/常用功能布局、新用户提示、底部奖励提示卡、「赚钱」按钮与蚕豆角标、运营匹配/BQT 模块；
     - **详情页/订单页**：「分享赚豆」浮动入口、霸王餐挑战、免费单入口、「领元宝/红包/奖励」按钮；
     - **全局**：浮动红包小窗；
     - **模块界面**：隐藏桌面图标（通过 `activity-alias` + `setComponentEnabledSetting` 从桌面移除本模块图标，仍可从 LSPosed 模块列表进入设置页重新开启）。
   - 设置通过 libxposed RemotePreferences 写入、宿主进程读取；关闭开关即恢复展示对应入口。广告与弹窗拦截始终开启，不受开关影响。
   - 修改开关后需强制停止并重启小蚕惠生活生效。

5. **网络层过滤 (Network Layer Filtering)**
   - 动态注入 OkHttp Interceptor，针对 `/g/pa` (placement 广告配置)、AdProLink、Sigmob、1rtb、Pangle、GDT、Kwad、Burying 埋点等流量实施本地拦截，返回纯净空响应。

6. **定位缓存 (Location Cache)**
   - 无需先开启 GPS 也能加载商家活动数据：宿主成功定位一次后，模块在腾讯定位 SDK 层 (`TencentLocationManager`) 缓存坐标与地址快照。
   - 之后关闭定位服务时，定位失败回调自动替换为缓存位置（error=0 成功回调），首页默认按缓存位置拉取商家活动数据；「选择地址」页同样可用。
   - 开启 GPS 后自动刷新缓存；系统层 `LocationManager` 定位结果作为兜底数据源。
   - 可在模块设置页「定位」分组关闭该功能。

7. **隐藏桌面图标**: 设置页新增「模块界面 - 隐藏桌面图标」开关, 关闭后从桌面移除本模块图标 (LAUNCHER 移入 `activity-alias`, 通过 `setComponentEnabledSetting` 切换); 可随时从 LSPosed 模块列表右下角按钮重新进入设置页开启.

## 回归测试

本地运行 `powershell -File tests/run.ps1`, 需准备 JDK 17、Gradle 9.5.1 和 Android SDK 37.
测试使用 JVM 接口替身调用生产 hook, 与真机验证分别记录.

## 构建与安装

- 编译环境：Microsoft JDK 17, Gradle 9.5.1, Android SDK 37 (compileSdk=37.0, minSdk=26, targetSdk=35)
- 产物路径：`XiaoCanPurify-1.0.0-release.apk`
- 安装步骤：
  1. 将生成的 APK 安装到设备。
  2. 在 LSPosed Manager 中启用「小蚕净化」模块。
  3. 作用域勾选「小蚕惠生活」(`com.realtech.xiaocan`)。
  4. 强制停止并重新启动小蚕惠生活
