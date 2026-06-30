# fonts/

## NotoSansSC-Regular.ttf

- **来源**: Google Noto Sans SC (Simplified Chinese)
- **许可证**: SIL Open Font License 1.1
- **下载**: https://fonts.google.com/noto/specimen/Noto+Sans+SC
- **用途**: 二维码图片底部学校名称渲染，确保 Linux 服务器上中文不显示为方块

## 更新/替换

若要替换字体文件：
1. 将新的 `.ttf` 文件放入此目录
2. 更新 `application.yml` 中的 `app.font.resource-path` 配置项
3. 重新构建部署
