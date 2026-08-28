# send-sms(Auth Send-SMS Hook)

Supabase 手机号 OTP 登录的短信发送通道:Supabase 生成并校验验证码,本函数把验证码经**阿里云短信**发出。

## 前置

1. 阿里云开通短信服务,申请**签名**(个人签名审核较严,提前办)和**验证码模板**(模板变量名必须是 `code`,如 `您的验证码是${code},5分钟内有效`)。
2. 创建仅有 `AliyunDysmsFullAccess` 权限的 RAM 用户,拿 AccessKey。

## 部署

```bash
# hook 请求来自 Supabase Auth(webhook 签名鉴权),不带用户 JWT
supabase functions deploy send-sms --no-verify-jwt

# 注意:Supabase secrets 注入非 ASCII 值会损坏,中文签名必须用 base64: 前缀
# printf '签名名称' | base64
supabase secrets set \
  ALIYUN_ACCESS_KEY_ID=xxx \
  ALIYUN_ACCESS_KEY_SECRET=xxx \
  ALIYUN_SMS_SIGN_NAME=base64:xxxx \
  ALIYUN_SMS_TEMPLATE_CODE=SMS_xxxxx
```

当前生产配置:签名「苏州智视妙言」、模板 `SMS_338340165`(变量 `code`)。

> 签名过审后运营商还需实名制端口报备(平均 5-7 个工作日),期间发送回执报
> `PORT_NOT_REGISTERED`(API 返回 OK 但运营商拒收)。报备状态看控制台签名管理页;
> 送达情况用 `QuerySendDetails` 查回执。

## 启用 Hook

Dashboard → Authentication → Hooks → **Send SMS hook** → 选择本 Edge Function,生成的 hook secret 存入:

```bash
supabase secrets set SMS_HOOK_SECRET="v1,whsec_xxxx"
```

同时在 Authentication → Providers → Phone 启用手机号登录。

## 防轰炸

- Supabase 内置 SMS 频率限制:Dashboard → Authentication → Rate Limits,建议同号码 60s 一条、每小时上限收紧。
- 阿里云侧也在短信服务控制台设置发送频率上限(同号码日限)。
