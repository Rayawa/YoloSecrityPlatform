import { webview } from '@kit.ArkWeb';
import { motion } from '@kit.MultimodalAwarenessKit';
import {
  hdsEffect,
  HdsNavigation,
  HdsNavigationTitleMode,
  TitleSize
} from '@kit.UIDesignKit';
import { curves, PromptAction, router, window } from '@kit.ArkUI';
import { harmonyShare, systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';

interface WebPageParams {
  url?: string;
}

// 分享链接中的演示口令需与后端 auth.share-token 保持一致。
const DEMO_SHARE_TOKEN: string = 'yolo-demo-share-2026';

@Entry
@Component
struct ArkWebPage {
  @Provide('pageStack') pageStack: NavPathStack = new NavPathStack();
  private webController: webview.WebviewController = new webview.WebviewController();
  private promptAction: PromptAction = this.getUIContext().getPromptAction();
  private context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;

  @State private url: string = '';
  private currentUrl: string = '';
  @State private isLoading: boolean = false;
  @State private progress: number = 0;
  @State private topPlaceholderHeight: number = 80;
  @State private isDemoLoginPending: boolean = true;

  // 课堂演示专用账号：只在 HarmonyOS WebView 内自动登录，不改变浏览器端和后端的鉴权规则。
  private readonly demoUsername: string = 'monitor';
  private readonly demoPassword: string = 'monitor123';
  private demoLoginStarted: boolean = false;
  private demoLoginTimeout: number = -1;
  private mainPageLoadFailed: boolean = false;

  @State private showButton: boolean = true;
  @State private buttonY: number = 300;
  @State private pageWidth: number = 360;
  @State private pageHeight: number = 780;
  @State private isPressed1: boolean = false;
  @State private isPressed2: boolean = false;
  @State private isRightHand: boolean = false;
  @State private funcTranslateX: number = 0;
  @State private funcScale: number = 1;
  @State private funcOpacity: number = 1;
  private isDragging: boolean = false;
  private dragStartY: number = 300;
  private lastSwitchTime: number = 0;
  private loadingTimer: number = -1;

  // 智感握持始终开启，不提供关闭开关。
  private holdingHandCallback: Callback<motion.HoldingHandStatus> | null = null;

  // 碰一碰分享与隔空抓取分别保留回调，确保可以准确注销。
  private isShareListening: boolean = false;
  private shareCapability?: harmonyShare.SendCapabilityRegistry;
  private readonly knockShareCallback: Callback<harmonyShare.SharableTarget> =
    (target: harmonyShare.SharableTarget): void => {
      this.shareToTarget(target);
    };
  private readonly gesturesShareCallback: Callback<harmonyShare.SharableTarget> =
    (target: harmonyShare.SharableTarget): void => {
      this.shareToTarget(target);
    };

  aboutToAppear(): void {
    const params = router.getParams() as WebPageParams | undefined;
    if (params?.url !== undefined && params.url.length > 0) {
      this.url = params.url;
      this.currentUrl = params.url;
    }
    this.subscribeHoldingHand();
    this.enablePassiveShare();
  }

  aboutToDisappear(): void {
    if (this.loadingTimer !== -1) {
      clearTimeout(this.loadingTimer);
      this.loadingTimer = -1;
    }
    if (this.demoLoginTimeout !== -1) {
      clearTimeout(this.demoLoginTimeout);
      this.demoLoginTimeout = -1;
    }
    this.unsubscribeHoldingHand();
    this.disablePassiveShare();
  }

  onBackPress(): boolean {
    try {
      if (this.webController.accessBackward()) {
        this.webController.backward();
        return true;
      }
    } catch (error) {
      console.warn(`WebView 返回失败: ${JSON.stringify(error)}`);
    }
    router.back();
    return true;
  }

  private subscribeHoldingHand(): void {
    if (this.holdingHandCallback !== null) {
      return;
    }
    if (!canIUse('SystemCapability.MultimodalAwareness.Motion')) {
      this.isRightHand = false;
      console.warn('当前设备不支持智感握持能力');
      return;
    }

    try {
      this.holdingHandCallback = (status: motion.HoldingHandStatus): void => {
        if (this.isDragging) {
          return;
        }
        const useRightHand = status === motion.HoldingHandStatus.RIGHT_HAND_HELD;
        if (useRightHand === this.isRightHand) {
          return;
        }
        const now = Date.now();
        if (now - this.lastSwitchTime < 2000) {
          return;
        }
        this.lastSwitchTime = now;
        animateTo({
          duration: 200,
          curve: Curve.FastOutSlowIn,
          onFinish: () => {
            this.isRightHand = useRightHand;
            this.funcTranslateX = useRightHand ? 120 : -120;
            animateTo({
              duration: 300,
              curve: curves.springMotion(0.4, 0.8)
            }, () => {
              this.funcTranslateX = 0;
              this.funcScale = 1;
              this.funcOpacity = 1;
            });
          }
        }, () => {
          this.funcTranslateX = this.isRightHand ? 120 : -120;
          this.funcScale = 0.3;
          this.funcOpacity = 0;
        });
      };
      motion.on('holdingHandChanged', this.holdingHandCallback);
      console.info('智感握持已开启');
    } catch (error) {
      this.holdingHandCallback = null;
      console.error(`智感握持开启失败: ${JSON.stringify(error)}`);
    }
  }

  private unsubscribeHoldingHand(): void {
    if (this.holdingHandCallback === null) {
      return;
    }
    try {
      if (canIUse('SystemCapability.MultimodalAwareness.Motion')) {
        motion.off('holdingHandChanged', this.holdingHandCallback);
      }
    } catch (error) {
      console.error(`智感握持注销失败: ${JSON.stringify(error)}`);
    } finally {
      this.holdingHandCallback = null;
    }
  }

  private getShareUrl(): string {
    try {
      const realUrl = this.webController.getUrl();
      if (realUrl && realUrl !== 'about:blank') {
        return realUrl;
      }
    } catch (error) {
      console.warn(`暂时无法读取 WebView URL: ${JSON.stringify(error)}`);
    }
    return this.currentUrl || this.url;
  }

  /**
   * 分享的是后端的演示会话入口。接收方访问后会建立 Session，再重定向到平台首页，
   * 因而不需要在另一台设备上输入账号密码。
   */
  private getDemoShareUrl(): string {
    const pageUrl: string = this.getShareUrl();
    const schemeEnd: number = pageUrl.indexOf('://');
    if (schemeEnd < 0) {
      return pageUrl;
    }
    const pathStart: number = pageUrl.indexOf('/', schemeEnd + 3);
    const origin: string = pathStart < 0 ? pageUrl : pageUrl.substring(0, pathStart);
    return `${origin}/api/auth/share?token=${DEMO_SHARE_TOKEN}`;
  }

  private createShareData(url: string): systemShare.SharedData {
    return new systemShare.SharedData({
      utd: uniformTypeDescriptor.UniformDataType.HYPERLINK,
      content: url,
      title: 'YOLO 智能安防平台',
      description: url
    });
  }

  private async shareToTarget(target: harmonyShare.SharableTarget): Promise<void> {
    const shareUrl = this.getDemoShareUrl();
    if (!shareUrl || shareUrl === 'about:blank') {
      await target.reject(harmonyShare.SharableErrorCode.NO_CONTENT_ERROR);
      return;
    }
    try {
      await target.share(this.createShareData(shareUrl));
      console.info('HarmonyShare 分享成功');
    } catch (error) {
      const businessError = error as BusinessError;
      console.error(`HarmonyShare 分享失败: ${businessError.code} ${businessError.message}`);
    }
  }

  private async enablePassiveShare(): Promise<void> {
    if (this.isShareListening || !canIUse('SystemCapability.Collaboration.HarmonyShare')) {
      return;
    }
    let knockRegistered: boolean = false;
    try {
      const appWindow = await window.getLastWindow(this.context);
      this.shareCapability = { windowId: appWindow.getWindowProperties().id };
      harmonyShare.on('knockShare', this.knockShareCallback);
      knockRegistered = true;
      harmonyShare.on('gesturesShare', this.shareCapability, this.gesturesShareCallback);
      this.isShareListening = true;
      console.info('碰一碰分享与隔空抓取已开启');
    } catch (error) {
      if (knockRegistered) {
        try {
          harmonyShare.off('knockShare', this.knockShareCallback);
        } catch (_) {
        }
      }
      this.shareCapability = undefined;
      const businessError = error as BusinessError;
      console.error(`HarmonyShare 监听失败: ${businessError.code} ${businessError.message}`);
    }
  }

  private disablePassiveShare(): void {
    if (!this.isShareListening) {
      return;
    }
    try {
      harmonyShare.off('knockShare', this.knockShareCallback);
    } catch (error) {
      console.error(`碰一碰分享注销失败: ${JSON.stringify(error)}`);
    }
    try {
      if (this.shareCapability !== undefined) {
        harmonyShare.off('gesturesShare', this.shareCapability, this.gesturesShareCallback);
      }
    } catch (error) {
      console.error(`隔空抓取注销失败: ${JSON.stringify(error)}`);
    } finally {
      this.isShareListening = false;
      this.shareCapability = undefined;
    }
  }

  private async showSystemShare(): Promise<void> {
    const shareUrl = this.getDemoShareUrl();
    if (!shareUrl || shareUrl === 'about:blank') {
      this.promptAction.showToast({ message: '当前页面暂无可分享链接' });
      return;
    }
    try {
      const shareController = new systemShare.ShareController(this.createShareData(shareUrl));
      await shareController.show(this.context, {
        previewMode: systemShare.SharePreviewMode.DETAIL,
        selectionMode: systemShare.SelectionMode.SINGLE
      });
    } catch (error) {
      const businessError = error as BusinessError;
      console.error(`系统分享失败: ${businessError.code} ${businessError.message}`);
    }
  }

  private clampButtonY(nextY: number): number {
    const topLimit = 100;
    const bottomLimit = Math.max(topLimit, this.pageHeight - 140);
    return Math.min(Math.max(nextY, topLimit), bottomLimit);
  }

  private restoreButtonAt(localY: number): void {
    this.showButton = true;
    this.buttonY = this.clampButtonY(localY - 50);
    const offset = this.isRightHand ? 120 : -120;
    this.funcTranslateX = offset;
    this.funcOpacity = 0;
    this.funcScale = 0.8;
    setTimeout(() => {
      animateTo({ duration: 300, curve: curves.springMotion(0.4, 0.8) }, () => {
        this.funcTranslateX = 0;
        this.funcOpacity = 1;
        this.funcScale = 1;
      });
    }, 0);
  }

  /**
   * 隐藏 Web 页面的退出入口，防止演示时误操作后重新落到登录页。
   * 该样式只注入当前 HarmonyOS WebView，不影响普通浏览器访问平台。
   */
  private hideDemoLoginControls(): Promise<string> {
    const script: string = `(() => {
      let style = document.getElementById('harmony-demo-style');
      if (!style) {
        style = document.createElement('style');
        style.id = 'harmony-demo-style';
        style.textContent = '.logout-button{display:none!important}';
        document.head.appendChild(style);
      }
      return 'ready';
    })()`;
    return this.webController.runJavaScript(script);
  }

  /**
   * 页面首次加载完成后，从页面同源调用现有登录接口。登录成功后 replace 当前页面，
   * WebView 会自动携带服务端下发的 JSESSIONID，从而直接显示主页面。
   */
  private startDemoLogin(): void {
    this.demoLoginStarted = true;
    const credentials: string = JSON.stringify({
      username: this.demoUsername,
      password: this.demoPassword
    });
    const script: string = `(() => {
      fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: ${JSON.stringify(credentials)}
      }).then((response) => {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        window.location.replace(window.location.href);
      }).catch((error) => {
        console.error('HarmonyOS demo auto login failed:', error);
      });
      return 'started';
    })()`;

    this.webController.runJavaScript(script).then(() => {
      // 网络或接口异常时解除遮罩，保留原登录页作为联调兜底。
      this.demoLoginTimeout = setTimeout(() => {
        if (this.isDemoLoginPending) {
          this.isDemoLoginPending = false;
          this.promptAction.showToast({
            message: '自动进入失败，请检查后端地址与网络',
            duration: 2000
          });
        }
        this.demoLoginTimeout = -1;
      }, 8000);
    }).catch((error: Error) => {
      console.error(`执行演示自动登录失败: ${JSON.stringify(error)}`);
      this.isDemoLoginPending = false;
      this.promptAction.showToast({ message: '无法自动进入平台', duration: 2000 });
    });
  }

  private finishDemoLogin(): void {
    if (this.demoLoginTimeout !== -1) {
      clearTimeout(this.demoLoginTimeout);
      this.demoLoginTimeout = -1;
    }
    this.hideDemoLoginControls().catch((error: Error) => {
      console.warn(`隐藏演示退出按钮失败: ${JSON.stringify(error)}`);
    }).finally(() => {
      this.isDemoLoginPending = false;
    });
  }

  build() {
    HdsNavigation(this.pageStack) {
      Stack() {
        Column() {
          // HdsNavigation 使用全局沉浸布局时，为标题栏和状态栏预留顶部内容区。
          Column()
            .height(30)
            .backgroundColor("#3862B7")
            .width('100%')
            .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP])

          Web({ src: this.url, controller: this.webController })
          .javaScriptAccess(true)
          .domStorageAccess(true)
          .metaViewport(true)
          .onNavigationEntryCommitted((details) => {
            if (details.url && details.url !== 'about:blank') {
              this.currentUrl = details.url;
            }
          })
          .onPageBegin((event) => {
            this.mainPageLoadFailed = false;
            this.isLoading = true;
            this.progress = 10;
            if (event !== undefined && event.url) {
              this.currentUrl = event.url;
            }
          })
          .onProgressChange((newProgress) => {
            this.progress = newProgress.newProgress;
          })
          .onErrorReceive((event) => {
            if (!event.request.isMainFrame()) {
              return;
            }
            this.mainPageLoadFailed = true;
            this.isLoading = false;
            this.isDemoLoginPending = false;
            console.error(`平台页面加载失败: ${event.error.getErrorCode()} ${event.error.getErrorInfo()}`);
            this.promptAction.showToast({
              message: '平台连接失败，请检查地址、Wi-Fi 和后端服务',
              duration: 2500
            });
          })
          .onPageEnd(() => {
            this.isLoading = false;
            this.progress = 100;
            this.currentUrl = this.getShareUrl();
            if (this.mainPageLoadFailed) {
              return;
            }
            if (!this.demoLoginStarted) {
              this.startDemoLogin();
            } else {
              this.finishDemoLogin();
            }
          })
          .width('100%')
          .layoutWeight(1)
        }
        .width('100%')
        .height('100%')

        if (this.isLoading) {
          Progress({ value: this.progress, total: 100 })
            .width('100%')
            .height(3)
            .position({ x: 0, y: this.topPlaceholderHeight })
            .zIndex(20)
        }

        if (this.isDemoLoginPending) {
          Column({ space: 14 }) {
            LoadingProgress()
              .width(42)
              .height(42)
              .color('#1768E5')
            Text('正在进入智能安防平台…')
              .fontSize(15)
              .fontColor('#344054')
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .width('100%')
          .height('100%')
          .backgroundColor('#F7FAFF')
          .zIndex(30)
        }

        // 沿用示例页面的两个悬浮按钮：回到顶部、刷新/停止。
        if (this.showButton) {
          Column({ space: 10 }) {
            Button() {
              Image($r('app.media.arrow_up')).width(24).height(24)
            }
            .onTouch((event: TouchEvent) => {
              if (event.type === TouchType.Down || event.type === TouchType.Move) {
                this.isPressed1 = true;
              } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
                this.isPressed1 = false;
              }
            })
            .onClick(() => {
              this.webController.scrollTo(0, 0);
              this.promptAction.showToast({ message: '回到顶部', duration: 500 });
            })
            .type(ButtonType.Circle)
            .width(45).height(45)
            .backgroundColor('rgba(255, 255, 255, 0.2)')
            .backgroundBlurStyle(BlurStyle.BACKGROUND_THIN, {
              colorMode: ThemeColorMode.SYSTEM,
              adaptiveColor: AdaptiveColor.AVERAGE,
              scale: 1.0
            })
            .linearGradientBlur(60, {
              fractionStops: [[0, 0], [0.5, 0.8], [1, 0]],
              direction: GradientDirection.Bottom
            })
            .visualEffect(new hdsEffect.HdsEffectBuilder()
              .pressShadow(this.isPressed1 ? hdsEffect.PressShadowType.BLEND_GRADIENT :
                hdsEffect.PressShadowType.NONE)
              .pointLight({
                illuminatedType: hdsEffect.PointLightIlluminatedType.BORDER_CONTENT,
                options: {
                  color: $r('app.color.light_color'),
                  intensity: this.isPressed1 ? 40.0 : 0.2,
                  height: 30.0
                } as hdsEffect.PointLightOptions
              }).buildEffect())
            .border({ width: 1, color: $r('app.color.button_border') })
            .borderRadius(22.5)

            Button() {
              Image(this.isLoading ? $r('app.media.stop') : $r('app.media.refresh')).width(24).height(24)
            }
            .onTouch((event: TouchEvent) => {
              if (event.type === TouchType.Down || event.type === TouchType.Move) {
                this.isPressed2 = true;
              } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
                this.isPressed2 = false;
              }
            })
            .onClick(() => {
              if (this.isLoading) {
                this.webController.stop();
                this.isLoading = false;
                return;
              }
              this.webController.refresh();
              this.isLoading = true;
              this.promptAction.showToast({ message: '刷新中...', duration: 1000 });
              if (this.loadingTimer !== -1) {
                clearTimeout(this.loadingTimer);
              }
              this.loadingTimer = setTimeout(() => {
                if (this.isLoading) {
                  this.isLoading = false;
                  this.promptAction.showToast({ message: '加载超时！', duration: 1000 });
                }
                this.loadingTimer = -1;
              }, 3000);
            })
            .type(ButtonType.Circle)
            .width(45).height(45)
            .backgroundColor('rgba(255, 255, 255, 0.2)')
            .backgroundBlurStyle(BlurStyle.BACKGROUND_THIN, {
              colorMode: ThemeColorMode.SYSTEM,
              adaptiveColor: AdaptiveColor.AVERAGE,
              scale: 1.0
            })
            .linearGradientBlur(60, {
              fractionStops: [[0, 0], [0.5, 0.8], [1, 0]],
              direction: GradientDirection.Bottom
            })
            .shadow(ShadowStyle.OUTER_DEFAULT_MD)
            .visualEffect(new hdsEffect.HdsEffectBuilder()
              .pressShadow(this.isPressed2 ? hdsEffect.PressShadowType.BLEND_GRADIENT :
                hdsEffect.PressShadowType.NONE)
              .pointLight({
                illuminatedType: hdsEffect.PointLightIlluminatedType.BORDER_CONTENT,
                options: {
                  color: $r('app.color.light_color'),
                  intensity: this.isPressed2 ? 40.0 : 0.2,
                  height: 30.0
                } as hdsEffect.PointLightOptions
              }).buildEffect())
            .border({ width: 1, color: $r('app.color.button_border') })
            .borderRadius(22.5)
          }
          .position({ x: this.isRightHand ? this.pageWidth - 53 : 8, y: this.buttonY })
          .translate({ x: this.funcTranslateX, y: 0 })
          .scale({ x: this.funcScale, y: this.funcScale })
          .opacity(this.funcOpacity)
          .zIndex(10)
          .gesture(
            GestureGroup(GestureMode.Exclusive,
              SwipeGesture({ direction: SwipeDirection.Horizontal })
                .onAction(() => {
                  const offset = this.isRightHand ? 120 : -120;
                  animateTo({
                    duration: 300,
                    curve: Curve.FastOutSlowIn,
                    onFinish: () => {
                      this.showButton = false;
                      this.funcTranslateX = 0;
                      this.funcOpacity = 1;
                      this.funcScale = 1;
                    }
                  }, () => {
                    this.funcTranslateX = offset;
                    this.funcOpacity = 0;
                    this.funcScale = 0.8;
                  });
                }),
              PanGesture({ direction: PanDirection.Vertical })
                .onActionStart(() => {
                  this.isDragging = true;
                  this.dragStartY = this.buttonY;
                })
                .onActionUpdate((event: GestureEvent) => {
                  this.buttonY = this.clampButtonY(this.dragStartY + event.offsetY);
                })
                .onActionEnd(() => {
                  this.isDragging = false;
                })
                .onActionCancel(() => {
                  this.isDragging = false;
                })
            )
          )
        }

        if (!this.showButton) {
          Column()
            .width(20).height('100%')
            .position({ x: 0, y: 0 })
            .zIndex(5)
            .gesture(TapGesture().onAction((event: GestureEvent) => {
              this.restoreButtonAt(event.fingerList[0].localY);
            }));

          Column()
            .width(20).height('100%')
            .position({ x: this.pageWidth - 20, y: 0 })
            .zIndex(5)
            .gesture(TapGesture().onAction((event: GestureEvent) => {
              this.restoreButtonAt(event.fingerList[0].localY);
            }));
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor($r('app.color.backcolor'))
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      .onAreaChange((_oldArea: Area, newArea: Area): void => {
        this.pageWidth = Number(newArea.width);
        this.pageHeight = Number(newArea.height);
        this.buttonY = this.clampButtonY(this.buttonY);
      })
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.backcolor'))
    .ignoreLayoutSafeArea(
      [LayoutSafeAreaType.SYSTEM],
      [LayoutSafeAreaEdge.TOP, LayoutSafeAreaEdge.BOTTOM]
    )
    .titleMode(HdsNavigationTitleMode.MINI)
    .hideBackButton(true)
    .divider({ color: $r('app.color.button_border') })
  }
}
