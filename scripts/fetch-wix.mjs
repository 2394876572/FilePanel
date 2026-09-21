// ============================================================================
//  FilePanel - fetch the WiX Toolset 3.x binaries needed by jpackage.
//
//  WHY THIS SCRIPT EXISTS
//  `jpackage --type msi` (and `--type exe`) are thin wrappers around the WiX
//  Toolset: they generate a .wxs source file and then call candle.exe/light.exe.
//  Without WiX on PATH, jpackage fails with "WiX is required". So building an
//  installer needs WiX - but only on the BUILD machine, never on the user's.
//
//  WHY THE ZIP AND NOT THE INSTALLER
//  WiX ships both an .exe installer and a plain binaries .zip. We take the zip:
//    - the .exe installer wants admin rights and writes into Program Files;
//    - the zip just unpacks, so the toolchain stays inside the project
//      (tools\wix314\) and the build stays reproducible and self-contained,
//      exactly like tools\apache-maven-3.9.9 and tools\m2repo already are.
//
//  WHY NODE
//  There is no curl/Invoke-WebRequest network access in this environment, but
//  Node can reach the network. Same reason scripts\fetch-maven.mjs exists.
//
//  Usage:  node scripts\fetch-wix.mjs           (download if missing)
//          node scripts\fetch-wix.mjs --list    (only show release assets)
// ============================================================================

import { createWriteStream } from 'node:fs';
import { mkdir, rm, stat } from 'node:fs/promises';
import { get } from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const RELEASE_API = 'https://api.github.com/repos/wixtoolset/wix3/releases/latest';
const ASSET_PATTERN = /^wix\d+-binaries\.zip$/i;

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectDir = dirname(scriptDir);
const targetDir = join(projectDir, 'tools', 'wix314');

const listOnly = process.argv.includes('--list');

/** Simple GET with redirect handling; GitHub redirects asset downloads to a CDN. */
function request(url, { followRedirects = true } = {}) {
    return new Promise((resolve, reject) => {
        get(url, { headers: { 'User-Agent': 'filepanel-build', Accept: 'application/vnd.github+json' } }, (res) => {
            if (followRedirects && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                res.resume();
                resolve(request(res.headers.location, { followRedirects }));
                return;
            }
            resolve(res);
        }).on('error', reject);
    });
}

function readBody(res) {
    return new Promise((resolve, reject) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => resolve(data));
        res.on('error', reject);
    });
}

async function download(url, destination) {
    const res = await request(url);
    if (res.statusCode !== 200) {
        throw new Error(`下载失败：HTTP ${res.statusCode}`);
    }
    const total = Number(res.headers['content-length'] || 0);
    await new Promise((resolve, reject) => {
        const out = createWriteStream(destination);
        let received = 0;
        let lastReported = -1;
        res.on('data', (chunk) => {
            received += chunk.length;
            if (total > 0) {
                const percent = Math.floor((received / total) * 100);
                if (percent >= lastReported + 20) {
                    lastReported = percent;
                    console.log(`  ...${percent}%（${(received / 1048576).toFixed(1)} MB）`);
                }
            }
        });
        res.pipe(out);
        out.on('finish', () => out.close(resolve));
        out.on('error', reject);
        res.on('error', reject);
    });
}

async function main() {
    console.log('[fetch-wix] 查询最新 WiX 3.x 发行版...');
    const releaseRes = await request(RELEASE_API);
    if (releaseRes.statusCode !== 200) {
        throw new Error(`查询发行版失败：HTTP ${releaseRes.statusCode}`);
    }
    const release = JSON.parse(await readBody(releaseRes));
    console.log(`[fetch-wix] ${release.tag_name}（${release.assets.length} 个附件）`);

    for (const asset of release.assets) {
        const mark = ASSET_PATTERN.test(asset.name) ? '  <== 需要这个' : '';
        console.log(`  ${asset.name}  ${(asset.size / 1048576).toFixed(1)} MB${mark}`);
    }
    if (listOnly) {
        return;
    }

    const asset = release.assets.find((a) => ASSET_PATTERN.test(a.name));
    if (!asset) {
        throw new Error('这个发行版里没有 -binaries.zip，请人工确认 WiX 的发行方式是否变了');
    }

    const candle = join(targetDir, 'candle.exe');
    try {
        await stat(candle);
        console.log(`[fetch-wix] 已存在，跳过下载：${candle}`);
        console.log('[fetch-wix] 想强制重下，先删掉 tools\\wix314 再运行。');
        return;
    } catch {
        // 不存在：继续下载
    }

    await mkdir(targetDir, { recursive: true });
    const zipPath = join(targetDir, asset.name);
    console.log(`[fetch-wix] 下载 ${asset.name} -> ${zipPath}`);
    await download(asset.browser_download_url, zipPath);

    const size = (await stat(zipPath)).size;
    console.log(`[fetch-wix] 下载完成：${(size / 1048576).toFixed(1)} MB`);
    console.log('[fetch-wix] 下一步（解压没有网络也能做，所以留在 PowerShell 里）：');
    console.log(`  Expand-Archive -Path "${zipPath}" -DestinationPath "${targetDir}" -Force`);
    console.log(`  Remove-Item "${zipPath}"`);
}

main().catch((err) => {
    console.error(`[fetch-wix] 失败：${err.message}`);
    console.error('[fetch-wix] 若网络不可用，也可以从另一台机器下载 wix*-binaries.zip 后');
    console.error(`[fetch-wix] 手工解压到：${targetDir}`);
    process.exit(1);
});
