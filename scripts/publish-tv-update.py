#!/usr/bin/env python3
"""Publishes the signed APK, matching source archive and update manifest to this repository."""
import hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

root=Path.cwd(); out=root/'outputs'; out.mkdir(exist_ok=True)

# Certificate of every release users can currently upgrade from. Changing it would break
# in-place installation, so it is asserted on every publish rather than trusted from Secrets.
RELEASE_CERT='4f4cf502b9ea2b2cccada54ab62031de1ef4dc9aecdf3addbcf4099dd853cf08'
TAG_RE=re.compile(r'kotlin-v(\d+)\.(\d+)\.(\d+)')
VERSION_RE=re.compile(r'\d+\.\d+\.\d+')
DEFAULT_NOTES='应用内自动检查和下载更新；保留安静连播、完整竖版海报和本机记录。'
# The block between these markers is what the TV shows in its update dialog. The rest of the
# file is the release page body. Markers rather than "the first paragraph" so that editing the
# prose cannot silently change what the TV reads.
NOTES_OPEN='<!-- notes -->'; NOTES_CLOSE='<!-- /notes -->'

def fail(message): raise SystemExit('::error::'+message)

def run(*args):
    """Runs a command and returns its stdout.

    A failure leaves through fail() with the command's own stderr attached. The stderr is
    the part that says what actually went wrong; a bare traceback buries it under a stack
    that says nothing about the APK, the certificate or the release.
    """
    result=subprocess.run(args,text=True,capture_output=True)
    if result.returncode!=0:
        detail=(result.stderr or result.stdout or '').strip() or f'(exit code {result.returncode}, no output)'
        fail(f'command failed: {" ".join(args)}\n{detail}')
    return result.stdout.strip()

def short_notes(text):
    """The block between the notes markers, or the whole text when there are no markers."""
    start=text.find(NOTES_OPEN); end=text.find(NOTES_CLOSE)
    if start<0 or end<start: return text.strip()
    return text[start+len(NOTES_OPEN):end].strip()

def release_notes_file():
    """The per-version notes document, when the repository has one."""
    path=os.environ.get('RELEASE_NOTES_FILE','').strip()
    if not path: return None
    file=Path(path)
    if not file.is_file(): return None
    return file

def manifest_notes():
    """What the TV shows when it offers this version.

    The short block in the per-version document wins; the environment variable is the
    fallback for a build whose notes were supplied from somewhere else; the generic
    sentence is the last resort. Before this existed every release carried the same
    sentence, so the update dialog said nothing about what had actually changed.
    """
    file=release_notes_file()
    if file is not None:
        block=short_notes(file.read_text(encoding='utf-8'))
        if block: return block
    return os.environ.get('RELEASE_NOTES','').strip() or DEFAULT_NOTES

def repo_args():
    """The repository this release belongs to, as arguments for gh.

    gh works it out from the working directory, and that is not dependable: a step that
    starts somewhere else - a CI step with a different working directory, a manual run from
    another directory - answers "release not found" about a release that plainly exists.
    The name comes from the environment when the workflow provides it, and from the origin
    remote otherwise.
    """
    repo=os.environ.get('GITHUB_REPOSITORY','').strip()
    if not repo:
        url=run('git','remote','get-url','origin').strip()
        url=re.sub(r'^(git@|https?://)','',url).replace(':','/')
        parts=[p for p in re.sub(r'\.git$','',url).split('/') if p]
        if len(parts)<2: fail(f'could not tell which repository this is (origin is {url!r}); set GITHUB_REPOSITORY')
        repo='/'.join(parts[-2:])
    return ['--repo',repo]

def version_key(tag):
    """Ordering key for a release tag, or None when the tag is not in the X.Y.Z scheme."""
    match=TAG_RE.fullmatch(tag or '')
    return tuple(int(part) for part in match.groups()) if match else None

def published_release_tags():
    """Tag names of the releases that are actually published.

    Drafts are left out on purpose: a draft is nothing a TV can download, and an interrupted
    run leaves one behind that a re-run has to be able to publish over.
    """
    args=['gh','release','list','--limit','1000','--json','tagName,isDraft']+repo_args()
    result=subprocess.run(args,text=True,capture_output=True)
    if result.returncode!=0:
        fail('could not list the published releases; refusing to publish without a version comparison')
    try:
        entries=json.loads(result.stdout or '[]')
    except ValueError:
        fail('could not read the release list; refusing to publish without a version comparison')
    return [entry.get('tagName','') for entry in entries if not entry.get('isDraft')]

def newest_published_version(tags):
    """The highest X.Y.Z version among the given tags, or None when there is none."""
    versions=[key for key in (version_key(tag) for tag in tags) if key]
    return max(versions) if versions else None

def dex_carries_version(apk, version):
    """True when the APK's dex carries this exact version string.

    Kotlin inlines BuildConfig constants into the bytecode. An incremental build that
    regenerates BuildConfig.java but skips recompiling Kotlin therefore leaves the *old*
    value in classes.dex while AndroidManifest.xml already reports the new one, so the app
    would show a stale version and judge updates against the wrong VERSION_CODE. Manifest
    checks cannot see that split; looking for the string in the dex can.
    """
    needle=version.encode('utf-8')
    with zipfile.ZipFile(apk) as archive:
        return any(needle in archive.read(n) for n in archive.namelist() if n.endswith('.dex'))

version=os.environ.get('HONGGUOTV_VERSION_NAME','').strip()
code_text=os.environ.get('HONGGUOTV_VERSION_CODE','').strip()
tag=os.environ.get('UPDATE_TAG','').strip()
if not VERSION_RE.fullmatch(version): fail(f'version must be X.Y.Z, got {version!r}')
if not TAG_RE.fullmatch(tag): fail(f'tag must be kotlin-vX.Y.Z, got {tag!r}')
if tag!=f'kotlin-v{version}': fail(f'tag {tag} does not match version {version}')
if not code_text.isdigit(): fail(f'VERSION_CODE must be a non-negative integer, got {code_text!r}')
code=int(code_text)

major,minor,patch=(int(part) for part in version.split('.'))
if minor>999 or patch>999: fail('minor and patch must stay below 1000 so the derived versionCode stays unique')
if code!=major*1_000_000+minor*1000+patch:
    fail(f'versionCode {code} does not match version {version}; expected {major*1_000_000+minor*1000+patch}')

# An older build must never take over `latest`, otherwise TVs stop seeing newer versions.
# The comparison is against *every* published kotlin-v release, not against the one that
# happens to hold the `latest` pointer: that pointer also serves the non-kotlin v* releases
# of the RN client, and a build published while it points at one of those would otherwise
# slip through unguarded and take the pointer back.
# The check runs before anything is created or uploaded, so a rejected build leaves no
# half-finished release behind.
previous=newest_published_version(published_release_tags())
refresh_only='--refresh-notes' in sys.argv
if refresh_only:
    # The pointer is not this path's business: the release already exists and keeps whatever
    # place it had. Only the words about it change.
    make_latest=None
elif previous is None:
    fail('no published kotlin-v release was found, so there is nothing to compare this build '
         'against; refusing to publish. If this really is the first kotlin-v release, publish it '
         'by hand (gh release create <tag> --latest) instead of through this script.')
else:
    make_latest=not previous>version_key(tag)

commit=run('git','rev-parse','HEAD')

def release_body(version, code, commit):
    """The release page body.

    The per-version document is the body when the repository has one - it is written for
    people, and it is the same text the television shows. The build facts are appended
    either way, because they are what a reader needs to reproduce the build.
    """
    facts=f'''\n\n---\n\nAndroid 8.0+ 原生独立版，版本 {version}（versionCode {code}）。\n\n应用在前台浏览时每 6 小时检查一次新版，下载并校验文件与签名后提示安装；设置 → 版本与更新可手动检查或关闭自动下载。电视需允许本应用安装未知来源应用；普通 Android 8 仍需确认系统安装，并非静默安装。\n\n保留安静连播、完整竖版海报和所有本机记录。使用同一发布签名，可直接覆盖此前正式版。\n\n构建源码提交：{commit}。JVM 测试与 Release lint 通过后才发布。\n\n- APK：电视安装文件。\n- source.zip：与 APK 对应的完整原生源码，GPL-3.0。\n- update.json：电视更新元数据，含版本、文件长度与 SHA-256。\n- SHA256SUMS：附件校验和。\n\n复现发布构建时设置 HONGGUOTV_VERSION_CODE={code} 和 HONGGUOTV_VERSION_NAME={version}，并使用同一发布证书。Android 8 实体电视兼容性需实机确认。\n'''
    file=release_notes_file()
    if file is not None:
        return file.read_text(encoding='utf-8').rstrip()+'\n'+facts
    return f'''Android 8.0+ 原生独立版，版本 {version}（versionCode {code}）。\n\n{DEFAULT_NOTES}\n'''+facts

def write_manifest(manifest):
    path=out/'update.json'
    path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return path

def refresh_published(tag, version, code):
    """Rewrites the manifest, the checksums and the body - and nothing else.

    A release's notes are usually written after the APK has been built and published: the
    television reads them out of update.json, and the body is what a person reads on the
    release page. Replacing the APK would be wrong - that is the file the TVs already
    installed - so this path touches only those three, and it checks the published APK
    against the manifest that is already up there before it changes anything.
    """
    with tempfile.TemporaryDirectory() as tmp:
        work=Path(tmp)
        run('gh','release','download',tag,*repo_args(),'--pattern','update.json','--dir',str(work),'--clobber')
        current=json.loads((work/'update.json').read_text(encoding='utf-8'))
        if current.get('versionCode')!=code or current.get('versionName')!=version:
            fail(f'the published manifest is for {current.get("versionName")} '
                 f'({current.get("versionCode")}), not {version} ({code}); refusing to rewrite it')
        run('gh','release','download',tag,*repo_args(),'--pattern',current['apk']['name'],'--dir',str(work),'--clobber')
        published=work/current['apk']['name']
        digest=hashlib.sha256(published.read_bytes()).hexdigest()
        if digest!=current['apk']['sha256'] or published.stat().st_size!=current['apk']['size']:
            fail('the published APK and the published manifest disagree about the APK; '
                 'refusing to rewrite the manifest on top of that')
        current['notes']=manifest_notes()
        manifest_path=write_manifest(current)
        # The checksums list the manifest's own digest, so that line changes with it and the
        # other two stay exactly as published.
        run('gh','release','download',tag,*repo_args(),'--pattern','SHA256SUMS','--dir',str(work),'--clobber')
        lines=[]
        for line in (work/'SHA256SUMS').read_text(encoding='utf-8').splitlines():
            parts=line.split('  ',1)
            if len(parts)==2 and parts[1]=='update.json':
                lines.append(f'{hashlib.sha256(manifest_path.read_bytes()).hexdigest()}  update.json')
            else:
                lines.append(line)
        checks=out/'SHA256SUMS'; checks.write_text('\n'.join(lines)+'\n',encoding='utf-8')
        run('gh','release','upload',tag,*repo_args(),str(manifest_path),str(checks),'--clobber')
    body=out/'release-notes.md'; body.write_text(release_body(version,code,commit),encoding='utf-8')
    run('gh','release','edit',tag,*repo_args(),'--notes-file',str(body))
    return current

if refresh_only:
    manifest=refresh_published(tag,version,code)
    info=json.loads(run('gh','release','view',tag,*repo_args(),'--json','assets'))
    for name in ('update.json','SHA256SUMS'):
        a=next((x for x in info['assets'] if x['name']==name),None)
        if not a or a['state']!='uploaded':
            fail(f'asset {name} did not upload intact')
    print(json.dumps({'tag':tag,'refreshed':'update.json, SHA256SUMS, release body','notes':manifest['notes'][:80]},ensure_ascii=False))
    raise SystemExit(0)

apk=out/f'hongguotv-kotlin-{version}-android8.apk'
shutil.copy2(root/'kotlin-tv/app/build/outputs/apk/release/app-release.apk',apk)

buildtools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
signature=run(str(buildtools/'apksigner'),'verify','--print-certs',str(apk))
if RELEASE_CERT not in signature:
    fail('APK is not signed with the current release certificate; users would have to uninstall and lose their library')

badging=run(str(buildtools/'aapt'),'dump','badging',str(apk))
if f"versionCode='{code}'" not in badging or f"versionName='{version}'" not in badging or "sdkVersion:'26'" not in badging:
    fail('built APK does not carry the requested versionCode/versionName/minSdk')
if not dex_carries_version(apk, version):
    fail(f'APK dex does not contain {version}: BuildConfig was not recompiled, so the app would '
         'show a stale version and compare updates against the wrong VERSION_CODE. Clean and rebuild.')

manifest={'schema':1,'packageName':'com.hongguotv.nativeapp','versionCode':code,'versionName':version,'minSdk':26,'tag':tag,
 'sourceCommit':commit,'apk':{'name':apk.name,'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},
 'notes':manifest_notes()}
write_manifest(manifest)

source=out/f'hongguotv-{tag}-source.zip'
run('git','archive','--format=zip',f'--prefix=hongguotv-{tag}/','--output',str(source),'HEAD')
notes=out/'release-notes.md'; notes.write_text(release_body(version,code,commit),encoding='utf-8')
checks=out/'SHA256SUMS'; checks.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in [apk,source,out/'update.json']),encoding='utf-8')

# Rerunning a published build must not overwrite the APK already installed on TVs.
existing=subprocess.run(['gh','release','view',tag,*repo_args(),'--json','isDraft'],text=True,capture_output=True)
if existing.returncode==0:
    if not json.loads(existing.stdout)['isDraft']:
        print('This build is already published; leaving its assets unchanged. '
              'Use --refresh-notes to rewrite only the manifest and the release body.')
        raise SystemExit(0)
else:
    run('gh','release','create',tag,*repo_args(),'--target',commit,'--draft','--title',f'红果 TV {version}','--notes-file',str(notes))

assets=[apk,source,out/'update.json',checks]
run('gh','release','upload',tag,*repo_args(),*map(str,assets),'--clobber')
info=json.loads(run('gh','release','view',tag,*repo_args(),'--json','assets'))
for p in assets:
    a=next(a for a in info['assets'] if a['name']==p.name)
    if a['state']!='uploaded' or a['size']!=p.stat().st_size or a['digest']!='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest():
        fail(f'asset {p.name} did not upload intact')

# `latest` was decided against every published kotlin-v release before anything was created.
run('gh','release','edit',tag,*repo_args(),'--draft=false','--latest='+str(make_latest).lower(),'--notes-file',str(notes))
print(json.dumps({'tag':tag,'version_code':code,'latest':make_latest,'sha256':manifest['apk']['sha256']}))
