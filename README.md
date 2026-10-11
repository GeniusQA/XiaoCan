# 小蚕净化 (XiaoCanPurify)

针对小蚕霸王餐 (`com.realtech.xiaocan`) 的现代化 Xposed / LSPosed API 102 净化模块。

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
   - 可通过开关移除中间的「会员」(VIP) 与「福利/赚钱」(Task/Benefit) 两个冗余标签。
   - 默认底部导航栏仅保留三个核心 Tab：
     1. **首页** (`HOME`)
     2. **订单** (`ORDER`)
     3. **我** (`USER`)
   - 联动 ViewPager2，Tab 索引无缝重映射，保留原有的到店/订单交互以及「我的」页面切换与未读提醒逻辑。

4. **功能开关 (Feature Toggles)**
   - 模块内置设置页，将原本全局隐藏的冗余功能入口改为**逐项开关控制**（默认开启 = 隐藏，保持净化）：
     - **底部导航栏**：会员 Tab、福利/赚钱 Tab、抖音补贴营销占位；
     - **首页**：顶部红包 Banner/活动背景、搜索右侧「抖音补贴」、红包快捷入口行、0元探店/到店霸王餐卡片、「开心收下」订单横条、浮动红包/新手引导、底部悬浮红包、下拉二楼；
     - **「我」页面**：挑战赛/免费抽奖活动行、霸王餐入驻/0元探店入驻横条、小蚕会员 SVIP 轮播卡、电商/生活/出行特惠网格、公告横幅、客服/常用功能布局、新用户提示、底部奖励提示卡、「赚钱」按钮与蚕豆角标、运营匹配/BQT 模块；
     - **详情页/订单页**：「分享赚豆」浮动入口、霸王餐挑战、免费单入口、「领元宝/红包/奖励」按钮；
     - **全局**：浮动红包小窗。
   - 设置通过 libxposed RemotePreferences 写入、宿主进程读取；关闭开关即恢复展示对应入口。广告与弹窗拦截始终开启，不受开关影响。
   - 修改开关后需强制停止并重启小蚕霸王餐生效。

5. **网络层过滤 (Network Layer Filtering)**
   - 动态注入 OkHttp Interceptor，针对 `/g/pa` (placement 广告配置)、AdProLink、Sigmob、1rtb、Pangle、GDT、Kwad、Burying 埋点等流量实施本地拦截，返回纯净空响应。

## 版本更新

当前版本: **1.0.0** (`versionCode=1`), 针对小蚕 **3.21.2** 核对并测试.

### 1.0.0 (2026-10-11)

- 模块设置页新增「功能开关」: 将原先全局写死隐藏的冗余功能入口改为逐项开关控制, 开关默认开启 (隐藏, 保持净化行为), 关闭即恢复展示对应入口.
- 开关分组覆盖: 底部导航栏 (会员 Tab / 福利赚钱 Tab / 抖音补贴营销位), 首页 (顶部红包 Banner、搜索右侧抖音补贴、红包快捷入口行、0元探店/到店霸王餐卡片、开心收下订单横条、浮动红包/新手引导、底部悬浮红包、下拉二楼), 「我」页面 (挑战赛/免费抽奖活动行、霸王餐入驻横条、SVIP 轮播卡、电商特惠网格、公告横幅、客服布局、新用户提示、底部奖励卡、赚钱按钮与蚕豆角标、运营匹配/BQT 模块), 详情页/订单页 (分享赚豆入口、霸王餐挑战、免费单入口、领元宝/红包/奖励按钮), 全局浮动红包小窗.
- 设置通过 libxposed RemotePreferences 从模块 App 写入、宿主进程读取, 并保留本地镜像; 服务未绑定或旧版环境自动回退默认净化行为.
- 「我」页面的菜单/客服/商家入驻/通知等纯功能入口不再被无差别整块隐藏, 可按需恢复展示.
- 底部导航栏: 仅在会员与福利 Tab 同时隐藏时才进入三 Tab 重映射模式, 单独恢复任一 Tab 时保留原生导航结构.
- 首页布局守护改为按开关逐项放行, 关闭开关的入口不再被反复折叠.
- 报名外跳保护、网络广告过滤、弹窗拦截持续默认开启, 不受开关影响.
- 修改开关后需强制停止并重启小蚕霸王餐生效.

### 历史开发记录

- 补全提现页与提现成功弹窗广告位拦截 (`WITHDRAWAL_SUCCESS_POPUP`、`WITHDRAWALPAGE_POPUP`、广告位 `8876` 及列表型 `resource_slug` 参数), 保留混合业务广告位与正常提现接口.
- 恢复正常下拉刷新, 只屏蔽下拉进入第二层.
- 补充首页运营弹窗、详情页 ToBid 广告与 Flutter 广告通道过滤.
- 移除提现右侧会员轮播及详情页「分享赚豆」浮动入口.
- 增加报名自动外跳保护及淘宝安装领红包提示拦截.
- 本地回归测试与发布构建/APK 签名校验通过, 保持原应用数据与登录状态.

## 回归测试

本地运行 `powershell -File tests/run.ps1`, 需准备 JDK 17、Gradle 9.5.1 和 Android SDK 37.
测试使用 JVM 接口替身调用生产 hook, 与真机验证分别记录.

## 构建与安装

- 编译环境：Microsoft JDK 17, Gradle 9.5.1, Android SDK 37 (compileSdk=37, minSdk=26, targetSdk=35)
- 产物路径：`XiaoCanPurify-1.0.0-release.apk`
- 安装步骤：
  1. 将生成的 APK 安装到设备。
  2. 在 LSPosed Manager 中启用「小蚕净化」模块。
  3. 作用域勾选「小蚕霸王餐」(`com.realtech.xiaocan`)。
  4. 强制停止并重新启动小蚕霸王餐即可享受清爽体验。
