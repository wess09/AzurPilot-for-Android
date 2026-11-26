"""用运行时内置 Git 和 Node.js 更新源码与前端，保留上游恢复协议。

候选源码与前端在正式目录外准备；锁文件变化交给整包更新。切换前保存恢复日志，
进程中断后由 android_host 在启动 WebUI 前回滚。旧运行时继续使用预构建前端。

Updates source and frontend with bundled Git and Node.js using upstream recovery protocols.

Prepares candidates outside the live tree and delegates lockfile changes to full updates.
A journal allows android_host to roll back interrupted switches before starting the WebUI.
Older runtimes continue using prebuilt frontend assets.
"""

import datetime
import hashlib
import importlib.abc
import importlib.machinery
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


STATE_FILE = 'config/android-update-state.json'
JOURNAL_FILE = 'config/android-update-transaction.json'
CNB_REPOSITORY = 'https://cnb.cool/AzurPilot/AzurPilot.git'
GITHUB_REPOSITORY = 'https://github.com/wess09/AzurPilot.git'


def write_json(path, value):
    """原子保存更新状态或恢复日志。 / Atomically saves update state or the recovery journal."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def now():
    """返回 UTC 状态时间。 / Returns the UTC state timestamp."""
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def tools_ready(root):
    """只为已内置工具链的新镜像启用本地更新。 / Enables local updates only for equipped images."""
    try:
        manifest = json.loads((Path(root) / 'BUILD_MANIFEST').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return False
    return (manifest.get('hot_update_mode') == 'local' and
            all(shutil.which(tool) for tool in ('git', 'node', 'npm')))


def git(root, *args, timeout=900):
    """限时执行 Git，失败时保留诊断输出。 / Runs bounded Git commands with failure diagnostics."""
    result = subprocess.run(
        ['git', '-c', 'http.lowSpeedLimit=1024', '-c', 'http.lowSpeedTime=30', *args],
        cwd=root, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace',
        timeout=timeout, env={**os.environ, 'GIT_TERMINAL_PROMPT': '0'},
    )
    return result.stdout.strip()


def workspace(root):
    """返回与正式源码同文件系统的更新目录。 / Returns an update workspace on the same filesystem."""
    return Path(root).parent / 'azurpilot-update'


def ensure_reload(root):
    """为本地热更启用上游父监督器，保持更新定时器及用户配置不变。

    Enables the upstream reload supervisor for local updates without changing timers or user settings.
    """
    if not tools_ready(root):
        return
    path = Path(root) / 'config/deploy.yaml'
    text = path.read_text(encoding='utf-8')
    updated = re.sub(r'(?m)^([ \t]*EnableReload:[ \t]*)false([ \t]*(?:#.*)?)$',
                     r'\g<1>true\g<2>', text, count=1)
    if updated != text:
        temporary = path.with_name(path.name + '.tmp')
        temporary.write_text(updated, encoding='utf-8')
        temporary.replace(path)


def recover(root):
    """在 WebUI 未运行时恢复未完成的源码、前端和清单切换；回滚失败时禁止启动。

    Restores interrupted source, frontend, and manifest switches before WebUI startup;
    a failed rollback prevents startup.
    """
    root = Path(root)
    journal = root / JOURNAL_FILE
    if journal.exists():
        state = json.loads(journal.read_text(encoding='utf-8'))
        old = state['oldHead']
        if not re.fullmatch(r'[0-9a-f]{40}', old):
            raise RuntimeError('更新恢复日志中的提交无效')
        for name in ('index.lock', 'HEAD.lock'):
            (root / '.git' / name).unlink(missing_ok=True)
        git(root, 'reset', '--hard', old)
        backup = workspace(root) / 'dist.previous'
        if backup.exists():
            shutil.rmtree(root / 'frontend/dist', ignore_errors=True)
            backup.replace(root / 'frontend/dist')
        write_json(root / 'BUILD_MANIFEST', state['manifest'])
        journal.unlink()
        write_json(root / STATE_FILE, {'phase': 'done', 'result': 'failed',
                                      'error': '更新中断，已恢复旧版本', 'updatedAt': now()})
    # 准备阶段尚未改正式版本，但 daemon 线程被杀后不能永久保留 busy 状态。
    state_path = root / STATE_FILE
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if state.get('phase') not in (None, 'done'):
            write_json(state_path, {'phase': 'done', 'result': 'failed',
                                    'error': '更新中断，请重试', 'updatedAt': now()})
    shutil.rmtree(workspace(root), ignore_errors=True)


def build_frontend(module, root):
    """按上游源码指纹和 npm 锁文件构建，构建失败保留现有 dist。

    Builds using upstream fingerprints and the npm lockfile, retaining the existing dist on failure.
    """
    directory = Path(root) / 'frontend'
    fingerprint = module.source_fingerprint(directory)
    marker = directory / 'dist/.source-fingerprint'
    if ((directory / 'dist/index.html').is_file() and marker.is_file() and
            marker.read_text(encoding='utf-8').strip() == fingerprint):
        return
    temporary = directory / '.android-dist'
    previous = directory / '.android-dist.previous'
    shutil.rmtree(temporary, ignore_errors=True)
    command = module.npm_command()
    ensure_dependencies = getattr(module, '_ensure_dependencies', None)
    if ensure_dependencies is None:
        # 旧镜像只提供三参数安装器，新版通过上游入口选安装方式并校验锁文件。
        module._install_dependencies(command, directory, {})
    else:
        ensure_dependencies(command, directory, {})
    try:
        subprocess.run([*command, 'run', 'build', '--', '--outDir', str(temporary.resolve())],
                       cwd=directory, check=True, timeout=600, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
        if not (temporary / 'index.html').is_file():
            raise RuntimeError('前端构建未生成页面入口')
        (temporary / '.source-fingerprint').write_text(fingerprint + '\n', encoding='utf-8')
        shutil.rmtree(previous, ignore_errors=True)
        dist = directory / 'dist'
        if dist.exists():
            dist.replace(previous)
        try:
            temporary.replace(dist)
        except Exception:
            if previous.exists():
                previous.replace(dist)
            raise
        shutil.rmtree(previous, ignore_errors=True)
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def patch_frontend(module):
    """为新镜像接入本地构建，旧镜像保留上游路径。 / Adds local builds while keeping legacy behavior."""
    original = module.ensure_frontend

    def ensure_frontend(root=None):
        root = Path(root) if root else Path(module.__file__).resolve().parents[1]
        if tools_ready(root):
            return build_frontend(module, root)
        return original(root)

    module.ensure_frontend = ensure_frontend


def patch_service(module):
    """替换 Android 更新服务的准备与切换阶段，保留 HTTP 接口及上游重载协议。

    Replaces preparation and switching while retaining HTTP APIs and upstream reload protocols.
    """
    original = module.AndroidUpdateService

    class LocalUpdateService(original):
        """在正式源码外准备可回滚的更新。 / Prepares reversible updates outside the live tree."""

        # 最近一次上游探测的失败原因；成功即清空，与 _ls_remote_cache 同步刷新。
        # Last upstream probe failure; cleared on success and refreshed with _ls_remote_cache.
        _ls_remote_error = ''

        def sources(self):
            """优先从 CNB 获取官方增量，失败时退回构建来源；自定义仓库保持原样。

            Prefers CNB for official updates with a build-source fallback; preserves custom sources.
            """
            manifest = module.frontend_deploy.android_manifest(self.root)
            repository = manifest.get('azurpilot_repo') or self.updater.Repository
            branch = manifest.get('azurpilot_branch') or 'dev'
            mirror = os.environ.get('AZURPILOT_ANDROID_GIT_MIRROR', '')
            if mirror and repository.startswith('https://github.com/'):
                repository = mirror + repository
            official = manifest.get('azurpilot_repo', '').rstrip('/').removesuffix('.git') == \
                GITHUB_REPOSITORY.removesuffix('.git')
            primary = manifest.get('azurpilot_update_repo') or (CNB_REPOSITORY if official else repository)
            return list(dict.fromkeys((primary, repository))), branch

        def upstream_head(self, force=False):
            """探测上游 HEAD，并记录失败原因供 status() 透出。

            裸吞异常会把「检查失败」伪装成「已是最新」（error 恒空、available 恒
            false），与 _run() 向上传递的约定相悖；失败原因带 git stderr 尾部，
            DNS/TLS 类故障可直接定位。
            / Probes the upstream HEAD and records the failure reason for status().

            Swallowed exceptions disguise "check failed" as "already up to date"
            (empty error, false available), contrary to _run()'s propagation
            convention; the reason carries the git stderr tail so DNS/TLS
            failures are directly diagnosable.
            """
            if not tools_ready(self.root):
                return super().upstream_head(force)
            cached = self._ls_remote_cache
            if not force and cached and time.monotonic() - cached[0] < 60:
                return cached[1]
            repositories, branch = self.sources()
            head = ''
            failure = ''
            for repository in repositories:
                try:
                    output = git(self.root, 'ls-remote', repository, f'refs/heads/{branch}', timeout=15)
                    head = output.split()[0] if output else ''
                except (OSError, subprocess.SubprocessError) as error:
                    head = ''
                    detail = (getattr(error, 'stderr', None) or str(error)).strip()
                    failure = f'{repository}: {detail}'[-300:]
                if re.fullmatch(r'[0-9a-f]{40}', head):
                    failure = ''
                    break
                head = ''
            self._ls_remote_cache = (time.monotonic(), head)
            self._ls_remote_error = failure
            return head

        def dist_ready(self, commit, force=False):
            return True if tools_ready(self.root) else super().dist_ready(commit, force)

        def status(self):
            if not tools_ready(self.root):
                return super().status()
            state = self._read_state()
            local = module.frontend_deploy.android_manifest(self.root).get('azurpilot_commit')
            busy = state.get('phase') not in (None, 'done')
            upstream = state.get('target') if busy else self.upstream_head()
            full = bool(upstream and state.get('target') == upstream and
                        state.get('requiresRuntimeUpdate'))
            # 任务自身的错误优先；仅空闲时透出探测失败——busy 期间不调
            # upstream_head()，旧错误会失真（App 侧已有「热更失败+原因」渲染分支）。
            # Task errors win; probe failures surface only while idle — upstream_head()
            # isn't called while busy, so a stale error would mislead (the app already
            # renders the "hot update failed + reason" branch).
            error = state.get('error', '') or ('' if busy else self._ls_remote_error)
            return {'enabled': True, 'localHead': local, 'upstreamHead': upstream or None,
                    'available': bool(upstream and local and local != upstream and not full),
                    'distReady': True, 'busy': busy, 'phase': state.get('phase'),
                    'target': state.get('target'), 'error': error,
                    'updatedAt': state.get('updatedAt'), 'requiresRuntimeUpdate': full}

        def _run(self, target):
            if not tools_ready(self.root):
                return super()._run(target)
            from module.runtime.setting import State, mark_dependency_sync_pending
            from module.runtime.startup_memory import mark_update_restart

            def reload():
                with State.restart_lock:
                    if State._restart_requested:
                        raise RuntimeError('WebUI 已请求重启')
                    from module.api.lifecycle import clearup
                    if not clearup():
                        raise RuntimeError('运行中的任务未能安全停止')
                    mark_update_restart()
                    State.dependency_sync_event.set()
                    State._restart_requested = True
                    State.restart_event.set()

            suspended = False
            full = False
            stage = workspace(self.root)
            try:
                if State.restart_event is None or State.dependency_sync_event is None:
                    raise RuntimeError('父监督器不可用，无法安全重启')
                # 首次升级旧镜像仍走旧通道；新镜像必须有可用于回滚的浅克隆。
                try:
                    old_head = git(self.root, 'rev-parse', 'HEAD')
                except (OSError, subprocess.SubprocessError) as error:
                    full = True
                    raise RuntimeError('源码仓库损坏，请更新完整运行时') from error
                manifest = module.frontend_deploy.android_manifest(self.root)
                if old_head != manifest.get('azurpilot_commit'):
                    full = True
                    raise RuntimeError('源码与版本记录不一致，请更新完整运行时')
                shutil.rmtree(stage, ignore_errors=True)
                stage.mkdir()
                candidate = stage / 'source'
                git(stage, 'clone', '--local', '--no-hardlinks', '--no-checkout',
                    str(self.root), str(candidate))
                repositories, _ = self.sources()
                for index, repository in enumerate(repositories):
                    git(candidate, 'remote', 'set-url', 'origin', repository)
                    try:
                        git(candidate, 'fetch', '--depth', '1', 'origin', target)
                        break
                    except (OSError, subprocess.SubprocessError):
                        if index == len(repositories) - 1:
                            raise
                git(candidate, 'reset', '--hard', target)
                # 禁止用新源码假装已安装新依赖；锁环境仍由整包构建和 OCR 门验证。
                if (candidate / 'uv.lock').read_bytes() != (self.root / 'uv.lock').read_bytes():
                    full = True
                    raise RuntimeError('Python 依赖已变化，请更新完整运行时')
                self._phase('dist')
                build_frontend(module.frontend_deploy, candidate)
                from module.runtime.process_manager import ProcessManager
                names = [instance.config_name + '\n' for instance in ProcessManager.running_instances()]
                mark_dependency_sync_pending()
                module.atomic_write('./config/reloadalas', ''.join(names))
                from module.api.lifecycle import clearup
                suspended = True
                if not clearup():
                    raise RuntimeError('运行中的任务未能安全停止')
                self._phase('manifest')
                # 正式源码只在构建成功后变化；journal 先落盘才能移动旧 dist。
                write_json(self.root / JOURNAL_FILE, {'oldHead': old_head, 'manifest': manifest})
                (self.root / 'frontend/dist').replace(stage / 'dist.previous')
                git(self.root, 'fetch', '--depth', '1', str(candidate), target)
                git(self.root, 'reset', '--hard', target)
                (candidate / 'frontend/dist').replace(self.root / 'frontend/dist')
                manifest.update(azurpilot_commit=target,
                                frontend_sha256=hashlib.sha256(
                                    (self.root / 'frontend/dist/index.html').read_bytes()).hexdigest(),
                                uv_lock_sha256=hashlib.sha256((self.root / 'uv.lock').read_bytes()).hexdigest(),
                                built_at_utc=now())
                write_json(self.root / 'BUILD_MANIFEST', manifest)
                self._phase('reload')
                # 先写完成态再重启，否则新 WebUI 可能继承 busy，永久阻塞后续更新。
                write_json(self.root / STATE_FILE, {'phase': 'done', 'result': 'updated',
                                                  'target': target, 'error': '', 'updatedAt': now()})
                reload()
                (self.root / JOURNAL_FILE).unlink()
            except Exception as error:
                module.logger.exception('[Android-热更] 本地更新失败')
                # 回滚失败保留 journal，让下一次启动再次恢复，不能删除唯一的旧 dist。
                recover_error = None
                try:
                    if (self.root / JOURNAL_FILE).exists():
                        recover(self.root)
                except Exception as rollback_error:
                    recover_error = rollback_error
                write_json(self.root / STATE_FILE, {'phase': 'done', 'result': 'failed',
                                                  'target': target, 'requiresRuntimeUpdate': full,
                                                  'error': (getattr(recover_error or error, 'stderr', None)
                                                            or str(recover_error or error))[-300:],
                                                  'updatedAt': now()})
                if suspended and recover_error is None and not State._restart_requested:
                    try:
                        reload()
                    except Exception:
                        module.logger.exception('[Android-热更] 恢复任务失败')
            finally:
                if not (self.root / JOURNAL_FILE).exists():
                    shutil.rmtree(stage, ignore_errors=True)

    module.AndroidUpdateService = LocalUpdateService
    module.android_update_service = LocalUpdateService()


class _Loader(importlib.abc.Loader):
    """在上游模块初始化后接入适配器。 / Connects adapters after upstream initialization."""

    def __init__(self, original, patch):
        self.original = original
        self.patch = patch

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        self.patch(module)


class _Finder(importlib.abc.MetaPathFinder):
    """只适配前端部署和 Android 更新模块。 / Adapts only frontend deployment and Android updates."""

    _android_update_adapter = True
    patches = {'deploy.frontend': patch_frontend, 'module.api.android_update': patch_service}

    def find_spec(self, fullname, path, target=None):
        if fullname not in self.patches:
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec and spec.loader:
            spec.loader = _Loader(spec.loader, self.patches[fullname])
        return spec


def install():
    """幂等安装延迟钩子，不提前导入上游。 / Installs lazy hooks idempotently without loading upstream."""
    if any(getattr(finder, '_android_update_adapter', False) for finder in sys.meta_path):
        return
    sys.meta_path.insert(0, _Finder())
    for name, patch in _Finder.patches.items():
        if name in sys.modules:
            patch(sys.modules[name])
