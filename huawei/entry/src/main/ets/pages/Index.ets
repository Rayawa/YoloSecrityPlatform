import { router } from '@kit.ArkUI';
import prompt from '@ohos.prompt';

@Entry
@Component
struct Index {
  @State url: string = '';

  private openWebPage(): void {
    let targetUrl: string = this.url.trim();
    if (targetUrl.length === 0) {
      prompt.showToast({
        message: '请输入网页 URL',
        duration: 1500
      });
      return;
    }

    // 没有填写协议时默认使用 http，方便输入局域网 IP 或开发服务器地址。
    if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
      targetUrl = `http://${targetUrl}`;
    }

    this.url = targetUrl;
    router.pushUrl({
      url: 'pages/arkweb',
      params: {
        url: targetUrl
      }
    }).catch(() => {
      prompt.showToast({
        message: '无法打开网页，请稍后重试',
        duration: 1500
      });
    });
  }

  build() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .linearGradient({
          angle: 145,
          colors: [
            ['#EEF5FF', 0.0],
            ['#F7FAFF', 0.55],
            ['#FFFFFF', 1.0]
          ]
        })

      Column({ space: 24 }) {
        Column({ space: 10 }) {
          Text('YOLO')
            .fontSize(38)
            .fontWeight(FontWeight.Bold)
            .fontColor('#1768E5')

          Text('智能安防平台')
            .fontSize(24)
            .fontWeight(FontWeight.Medium)
            .fontColor('#182431')

          Text('输入平台地址，进入实时监控页面')
            .fontSize(14)
            .fontColor('#697386')
        }
        .alignItems(HorizontalAlign.Center)

        Column({ space: 12 }) {
          Text('网页 URL')
            .width('100%')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .fontColor('#344054')

          TextInput({
            placeholder: '例如：http://192.168.1.10:8080',
            text: this.url
          })
            .width('100%')
            .height(52)
            .fontSize(15)
            .fontColor('#182431')
            .placeholderColor('#98A2B3')
            .backgroundColor('#F7F9FC')
            .border({ width: 1, color: '#D7DFEA' })
            .borderRadius(12)
            .padding({ left: 14, right: 14 })
            .onChange((value: string) => {
              this.url = value;
            })

          Button('进入平台')
            .width('100%')
            .height(50)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .fontColor(Color.White)
            .backgroundColor('#1768E5')
            .borderRadius(12)
            .margin({ top: 8 })
            .onClick(() => {
              this.openWebPage();
            })
        }
        .width('100%')
        .padding(22)
        .backgroundColor(Color.White)
        .borderRadius(20)
        .shadow({
          radius: 18,
          color: '#1A1768E5',
          offsetY: 8
        })
      }
      .width('88%')
      .constraintSize({ maxWidth: 520 })
    }
    .width('100%')
    .height('100%')
    .alignContent(Alignment.Center)
  }
}
