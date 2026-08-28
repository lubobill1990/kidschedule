// Supabase Auth Send-SMS Hook -> 阿里云短信
// Supabase 负责生成/校验 OTP 与频率限制,本函数只负责把 OTP 发出去。
// 部署说明见同目录 README.md

import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

const HOOK_SECRET = Deno.env.get("SMS_HOOK_SECRET") ?? "";
// mock:不真发短信,只把 OTP 打进函数日志(测试用);aliyun:真发
const TRANSPORT = Deno.env.get("SMS_TRANSPORT") ?? "aliyun";
const ALIYUN_AK_ID = Deno.env.get("ALIYUN_ACCESS_KEY_ID") ?? "";
const ALIYUN_AK_SECRET = Deno.env.get("ALIYUN_ACCESS_KEY_SECRET") ?? "";
// 签名是中文,secrets 注入非 ASCII 可能被损坏 → 支持 base64:前缀,或直接用默认值
function decodeSignName(): string {
  const raw = Deno.env.get("ALIYUN_SMS_SIGN_NAME") ?? "";
  if (raw.startsWith("base64:")) {
    return new TextDecoder().decode(
      Uint8Array.from(atob(raw.slice(7)), (c) => c.charCodeAt(0)),
    );
  }
  return raw;
}
const SIGN_NAME = decodeSignName();
const TEMPLATE_CODE = Deno.env.get("ALIYUN_SMS_TEMPLATE_CODE") ?? "";

// 阿里云 RPC 签名要求的特殊 URL 编码
function specialEncode(v: string): string {
  return encodeURIComponent(v)
    .replace(/\+/g, "%20")
    .replace(/\*/g, "%2A")
    .replace(/%7E/g, "~");
}

async function hmacSha1Base64(key: string, msg: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(msg));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

async function sendAliyunSms(phone: string, otp: string): Promise<void> {
  // Supabase 的 phone 形如 "8613800138000",国内模板发送需去掉 86 前缀
  const domesticPhone = phone.startsWith("86") ? phone.slice(2) : phone;

  const params: Record<string, string> = {
    AccessKeyId: ALIYUN_AK_ID,
    Action: "SendSms",
    Format: "JSON",
    PhoneNumbers: domesticPhone,
    RegionId: "cn-hangzhou",
    SignName: SIGN_NAME,
    SignatureMethod: "HMAC-SHA1",
    SignatureNonce: crypto.randomUUID(),
    SignatureVersion: "1.0",
    TemplateCode: TEMPLATE_CODE,
    TemplateParam: JSON.stringify({ code: otp }),
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    Version: "2017-05-25",
  };

  const sortedQuery = Object.keys(params)
    .sort()
    .map((k) => `${specialEncode(k)}=${specialEncode(params[k])}`)
    .join("&");
  const stringToSign = `GET&${specialEncode("/")}&${specialEncode(sortedQuery)}`;
  const signature = await hmacSha1Base64(`${ALIYUN_AK_SECRET}&`, stringToSign);

  const url = `https://dysmsapi.aliyuncs.com/?Signature=${specialEncode(signature)}&${sortedQuery}`;
  const resp = await fetch(url);
  const body = await resp.json();
  if (body.Code !== "OK") {
    const signHex = [...SIGN_NAME].map((c) => c.codePointAt(0)!.toString(16)).join(",");
    throw new Error(`aliyun sms failed: ${body.Code} ${body.Message} (sign=[${signHex}])`);
  }
}

Deno.serve(async (req) => {
  const payload = await req.text();
  const headers = Object.fromEntries(req.headers);

  let data: { user: { phone: string }; sms: { otp: string } };
  try {
    const wh = new Webhook(HOOK_SECRET.replace("v1,whsec_", ""));
    data = wh.verify(payload, headers) as typeof data;
  } catch {
    return new Response(JSON.stringify({ error: "invalid webhook signature" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  try {
    if (TRANSPORT === "mock") {
      console.log(`[mock-sms] phone=${data.user.phone} otp=${data.sms.otp}`);
    } else {
      await sendAliyunSms(data.user.phone, data.sms.otp);
    }
    return new Response("{}", { headers: { "Content-Type": "application/json" } });
  } catch (e) {
    console.error(e);
    return new Response(JSON.stringify({ error: String(e) }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
