#!/usr/bin/env python3
"""Export only tracked native-client source at HEAD, without private Git history or local files.

Retired 2026-10-17. The source repository is now public, so .github/workflows/release.yml builds
and publishes straight from it and this mirror step is no longer part of the release path. The
script is kept intact so the old two-repository flow stays reversible, but note that its
allow-list below still names N3urda/hongguoTV-updates, which has never had a release. The live
publishing path is a kotlin-vX.Y.Z tag, not this script.

What it does to the target repository is destructive: it removes kotlin-tv/, scripts/,
server/, README.md, LICENSE, .gitignore and .github/workflows/ from the target's work tree
and stages the result. It does not commit and it does not push, so a run that is interrupted
leaves the target deleted but uncommitted. The guards below keep that from happening by
accident:
  * nothing happens unless --force is given,
  * the target's origin has to be on the allow-list - a dedicated PUBLIC update repository,
  * the target may not be this repository,
  * --dry-run prints the whole plan and changes nothing.
"""
import argparse
import subprocess
import sys
from pathlib import Path

# The only repositories this script may ever write to. Point it at a dedicated public update
# repository - never at the source repository.
ALLOWED_REMOTES = (
    'git@github.com:N3urda/hongguoTV-updates.git',
    'https://github.com/N3urda/hongguoTV-updates.git',
)

# The paths the export replaces in the target repository.
REPLACED = ('kotlin-tv', 'scripts', 'server', 'README.md', 'LICENSE', '.gitignore', '.github/workflows')

# The two files the mirror needs that the tracked export above does not carry: the release
# workflow and the publish script. They used to live in kotlin-tv/distribution/, which the
# tag-driven workflow removed in c59aa19; they now come straight from this repository.
DISTRIBUTION = (
    ('.github/workflows/release.yml', '.github/workflows/release.yml'),
    ('scripts/publish-tv-update.py', 'scripts/publish-tv-update.py'),
)

root = Path(__file__).resolve().parent.parent

def git(*args, cwd=root): return subprocess.check_output(['git', *args], cwd=cwd)

def remote_of(target):
    try:
        return git('remote', 'get-url', 'origin', cwd=target).decode().strip()
    except subprocess.CalledProcessError:
        raise SystemExit('Target has no origin remote; refusing to guess where this would be published to')

def exported_paths():
    paths = git('ls-tree', '-r', '--name-only', '-z', 'HEAD', '--', 'kotlin-tv', 'LICENSE', 'scripts/build-kotlin.sh', 'server/vendor/hongguo.cjs', 'server/vendor/LICENSE.drpys').decode().split('\0')
    return [p for p in paths if p and not p.startswith('kotlin-tv/distribution/')]

def check_paths(paths):
    for name in paths:
        if any(part in ('build', '.gradle', '.kotlin') for part in Path(name).parts) or name.endswith(('.jks', '.keystore', 'keystore.properties', 'local.properties')):
            raise SystemExit('Local-only material unexpectedly tracked; refusing export')

def plan(target, remote, paths):
    removed = [p for p in REPLACED if (target / p).exists()]
    return {'target': target, 'remote': remote, 'paths': paths, 'removed': removed}

def show_plan(the_plan):
    print(f"Target repository : {the_plan['target']}")
    print(f"Target origin     : {the_plan['remote']}")
    print(f"Removed there     : {', '.join(the_plan['removed']) or '(nothing present)'}")
    print(f"Exported files    : {len(the_plan['paths'])} tracked source files")
    for name in the_plan['paths'][:10]:
        print(f"  + {name}")
    if len(the_plan['paths']) > 10:
        print(f"  ... and {len(the_plan['paths']) - 10} more")
    print("Staged in the target afterwards; nothing is committed and nothing is pushed.")

def main() -> int:
    parser = argparse.ArgumentParser(description='Export the tracked native source into a public update repository.',
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('target', help='path of the PUBLIC update repository to export into')
    parser.add_argument('--force', action='store_true', help='actually rewrite the target; without it nothing happens')
    parser.add_argument('--dry-run', action='store_true', help='print the plan and change nothing')
    args = parser.parse_args()

    target = Path(args.target).resolve()
    if not (target / '.git').exists():
        raise SystemExit(f'Target is not a git repository: {target}')
    if target == root:
        raise SystemExit('Refusing to export into this repository; that would delete the source tree.')
    remote = remote_of(target)
    if remote not in ALLOWED_REMOTES:
        raise SystemExit('Target must be the dedicated public update repository; '
                         f'origin is {remote!r}, which is not on the allow-list.')

    paths = exported_paths()
    check_paths(paths)
    the_plan = plan(target, remote, paths)

    if args.dry_run:
        print('DRY RUN - nothing was changed.')
        show_plan(the_plan)
        return 0
    if not args.force:
        print('REFUSING TO RUN: this script rewrites the target repository and needs --force.')
        show_plan(the_plan)
        print('\nRun it with --dry-run to see this plan, or with --force to apply it.')
        return 1

    git('rm', '-r', '--ignore-unmatch', '--', *REPLACED, cwd=target)
    for name in paths:
        destination = target / name; destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(git('show', 'HEAD:' + name))
        if name.endswith('/gradlew') or name == 'scripts/build-kotlin.sh': destination.chmod(0o755)
    for source, name in DISTRIBUTION:
        destination = target / name; destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(git('show', 'HEAD:' + source))
    (target / '.gitignore').write_text('local.properties\nkeystore.properties\n*.jks\n*.keystore\n.gradle/\n.kotlin/\nbuild/\noutputs/\n')
    (target / 'README.md').write_text('''# 红果 TV · 原生客户端与自动更新

[下载最新版 APK](https://github.com/N3urda/hongguoTV-updates/releases/latest) · [构建状态](https://github.com/N3urda/hongguoTV-updates/actions/workflows/release.yml)

Android 8.0+，Kotlin / Media3，单 APK，无需部署服务。非官方、自用电视客户端。

首次手动安装带更新功能的正式版后，应用在前台浏览时每 6 小时检查一次新版，下载并校验后提示安装。播放时停止更新请求；无需 GitHub 登录。设置 → 版本与更新可手动检查或关闭自动下载。Android 8 仍需允许本应用安装未知来源应用，并确认系统安装；并非静默安装。

保留短剧/漫剧浏览、搜索、收藏、观看记录、续播、选集、全局清晰度与倍速。沿用原发布签名，覆盖安装保留本机数据。平台账号同步尚未接通。

本仓库只包含可发布的原生源码及构建文件，使用 GPL-3.0。main 每次更新会通过 GitHub Actions 运行测试、Release lint，生成签名 APK，再发布 APK、对应源码、update.json 和校验和。源码不包含发布私钥；签名密钥仅配置于 Actions Secrets。

构建开发 APK（JDK 17、Android SDK 36）：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
```

正式构建使用 `scripts/build-kotlin.sh`；签名配置见 [原生构建说明](kotlin-tv/README.md)。版本号由 kotlin-vX.Y.Z tag 决定：versionName 即 tag 中的 X.Y.Z，versionCode 为 major*1000000 + minor*1000 + patch（kotlin-v0.9.47 对应 9047），CI 另有单调性守卫拒绝比已发布版本旧的 tag。Release 的 update.json 记录确切版本和源码提交；复现发布构建时传入同样的 HONGGUOTV_VERSION_CODE 与 HONGGUOTV_VERSION_NAME。

Android 8 实体电视的解码、音频、遥控器和系统安装入口仍需实机确认。
''')
    git('add', '--', 'kotlin-tv', 'scripts', 'server', 'README.md', 'LICENSE', '.gitignore', '.github/workflows/release.yml', cwd=target)
    print(f'Exported {len(paths)} tracked native source files; private history and untracked files excluded.')
    print(f'The changes are staged in {target} and nothing else: review them, then commit and push yourself.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
