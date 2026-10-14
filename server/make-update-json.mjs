// SPDX-License-Identifier: GPL-3.0-only
// 生成 App 内自动更新用的 update.json。
//
// AppUpdateRepository 请求 {RELEASES}/latest/download/update.json，也就是「最新 release 里名为
// update.json 的附件」。文件不存在时 GitHub 返回 404，App 会显示「尚无可用的自动更新版本」，
// 所以每次发版都必须把它和 APK 一起挂上去，否则检查更新这一路等于没接。
//
// 用法：node server/make-update-json.mjs <apk 路径> <tag> <versionCode> <versionName> [notes]
import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';

const [, , apkPath, tag, versionCode, versionName, ...rest] = process.argv;
if (!apkPath || !tag || !versionCode || !versionName) {
  console.error('用法: node make-update-json.mjs <apk> <tag> <versionCode> <versionName> [notes]');
  process.exit(1);
}

const notes = rest.join(' ').slice(0, 1500);
const bytes = readFileSync(apkPath);
const sha256 = createHash('sha256').update(bytes).digest('hex');
const name = basename(apkPath);

// AppUpdate.parse 会逐项校验这些字段，改动前先对齐那边的正则：
//   packageName 必须是 com.hongguotv.nativeapp
//   versionName 必须匹配 [0-9][0-9A-Za-z.+-]{0,63}
//   tag         必须匹配 kotlin-v[0-9][0-9A-Za-z.+-]{0,80}
//   apk.name    必须匹配 hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android8\.apk
const doc = {
  schema: 1,
  packageName: 'com.hongguotv.nativeapp',
  versionCode: Number(versionCode),
  versionName,
  minSdk: 26,
  tag,
  notes,
  apk: { name, size: statSync(apkPath).size, sha256 },
};

const out = JSON.stringify(doc, null, 2);
console.log(out);
writeFileSync('update.json', out + '\n', 'utf8');
console.error('\nwrote update.json');
