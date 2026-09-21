/**
 * M0 引导脚本：下载并解压项目自带的 Apache Maven。
 *
 * 为什么不用 IDEA 内置的 Maven：内置版本随 IDE 升级而变，且路径含空格与中文风险，
 * 项目自带一份可保证任何人在任何机器上构建结果一致（回退方案见 scripts/mvn.cmd）。
 *
 * 用法：node scripts/fetch-maven.mjs
 * 说明：本机 curl / Invoke-WebRequest 走 schannel 被沙箱拦截，Node 的 fetch 可用，故用 Node 下载。
 */
import { createWriteStream } from 'node:fs';
import { mkdir, rm, stat } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MAVEN_VERSION = '3.9.9';
const ZIP_NAME = `apache-maven-${MAVEN_VERSION}-bin.zip`;
// 优先 Maven Central（本机实测可达）；archive.apache.org 在本机不通
const URLS = [
  `https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/${ZIP_NAME}`,
];

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const toolsDir = path.join(root, 'tools');
const zipPath = path.join(toolsDir, ZIP_NAME);
const mavenHome = path.join(toolsDir, `apache-maven-${MAVEN_VERSION}`);

async function exists(p) {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
}

async function download() {
  let lastErr;
  for (const url of URLS) {
    try {
      console.log(`[fetch-maven] 尝试下载: ${url}`);
      const res = await fetch(url, { signal: AbortSignal.timeout(180_000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const total = Number(res.headers.get('content-length') ?? 0);
      let done = 0;
      let lastLog = 0;
      const body = Readable.fromWeb(res.body);
      body.on('data', (chunk) => {
        done += chunk.length;
        if (done - lastLog > 2_000_000) {
          lastLog = done;
          const pct = total ? ((done / total) * 100).toFixed(0) + '%' : '';
          console.log(`[fetch-maven]   已下载 ${(done / 1048576).toFixed(1)}MB ${pct}`);
        }
      });
      await pipeline(body, createWriteStream(zipPath));
      console.log(`[fetch-maven] 下载完成: ${zipPath}`);
      return;
    } catch (e) {
      lastErr = e;
      console.warn(`[fetch-maven] 失败 (${e.name}: ${e.message})，尝试下一个源`);
    }
  }
  throw new Error(`全部下载源均失败: ${lastErr?.message}`);
}

async function main() {
  await mkdir(toolsDir, { recursive: true });

  if (await exists(mavenHome)) {
    console.log(`[fetch-maven] 已存在，跳过解压: ${mavenHome}`);
  } else {
    if (!(await exists(zipPath))) {
      await download();
    } else {
      console.log(`[fetch-maven] 复用已下载的压缩包: ${zipPath}`);
    }
    console.log('[fetch-maven] 解压中（使用 Windows 自带 tar）...');
    execFileSync('tar', ['-xf', zipPath, '-C', toolsDir], { stdio: 'inherit' });
    await rm(zipPath, { force: true });
    console.log('[fetch-maven] 解压完成，已删除压缩包');
  }

  const mvnCmd = path.join(mavenHome, 'bin', 'mvn.cmd');
  if (!(await exists(mvnCmd))) {
    throw new Error(`解压后未找到 ${mvnCmd}`);
  }
  console.log(`[fetch-maven] OK -> ${mvnCmd}`);
}

main().catch((e) => {
  console.error(`[fetch-maven] 失败: ${e.message}`);
  process.exit(1);
});
