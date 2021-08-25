import abc
import argparse
import enum
import json
import logging
import os
import re
import subprocess
import time
from abc import ABCMeta
from pathlib import Path
import waiting

import luigi
import luigi.format
import pandas as pd
import requests
from luigi import LocalTarget
from typing import List

logger = logging.getLogger('luigi-interface')


class FrontmatterSourceType(enum.Enum):
    apk = 1
    sha = 2


class FrontmatterRepoType(enum.Enum):
    local = 1
    androzoo = 2


class FrontmatterConfig(luigi.Config):
    apks_folder: str = luigi.Parameter(significant=False)
    pkg_in_path: str = luigi.BoolParameter(default=False, significant=False)
    frontmatter_path = luigi.Parameter(significant=False)
    timeout: int = luigi.IntParameter(significant=False)
    resolver_timeout: int = luigi.IntParameter(significant=False)
    android_platform = luigi.Parameter(significant=False)
    xmx = luigi.Parameter(default="15g", significant=False)
    xss = luigi.Parameter(default="100m", significant=False)
    source = luigi.Parameter(default="zoo", significant=False)
    delete_apk: str = luigi.BoolParameter(default=False, significant=False)
    path_prefix: str = luigi.Parameter(significant=False)
    androzoo_csv: str = luigi.Parameter(significant=False)
    from_google_play: bool = luigi.BoolParameter(significant=False)

    @staticmethod
    def get_apk_path(apk_name):
        apk_path = Path(config.apks_folder)
        if config.pkg_in_path:
            pkg = FrontmatterConfig.get_pkg(apk_name)
            return apk_path / pkg / apk_name
        else:
            return apk_path / f"{apk_name}.apk"

    @staticmethod
    def get_path(file: str, pkg: str) -> Path:
        path = Path(file)
        if not path.is_absolute():
            path = Path(config.path_prefix) / file
        if config.pkg_in_path:
            return path / pkg
        else:
            return path

    @staticmethod
    def get_apk_data(apk_name):
        pattern = re.compile(r"(?P<pkg>.+?)(--(?P<ver>[0-9]+))?(--(?P<date>[0-9\-]+))?$")
        match = pattern.match(apk_name)
        if match is None:
            raise ValueError("Problem with file name: {fn}".format(fn=apk_name))
        pkg = match.group("pkg")
        vercode = match.group("ver")
        date = match.group("date")
        return pkg, vercode, date

    @staticmethod
    def get_pkg(apk_name):
        pkg, _, _ = FrontmatterConfig.get_apk_data(apk_name)
        return pkg

    @staticmethod
    def get_apk_name(pkg, vercode=None, date=None):
        name_components = [x for x in [pkg, vercode, date] if x is not None]
        apk_name = '--'.join(name_components)
        return apk_name


config = FrontmatterConfig()


# class ZooLoaderTask(luigi.Task):
#     sha_file: str = luigi.Parameter()
#     androzoo_csv: str = luigi.Parameter(significant=False)
#     apps = luigi.ListParameter(significant=False)
#     s_type = luigi.EnumParameter(enum=FrontmatterSourceType, default=FrontmatterSourceType.apk)
#     from_google_play: bool = luigi.BoolParameter(significant=False, default=True)

# def output(self):
#     return LocalTarget(path=self.sha_file)

class ZooLoaderTask:

    def __init__(self, androzoo_csv, from_google_play: bool = True):
        self.androzoo_csv = androzoo_csv
        self.from_google_play = from_google_play

    def run(self, sha_file: Path, s_type: FrontmatterSourceType, apps: List[str]):
        lock_file = sha_file.with_suffix(".lock")
        if not sha_file.exists():
            if not lock_file.exists():
                lock_file.touch(exist_ok=False)
                latest = pd.read_csv(self.androzoo_csv, dtype={'vercode': str})
                latest = latest[['pkg_name', 'vercode', 'sha256', 'markets']]
                if self.from_google_play:
                    latest = latest[latest.markets.str.contains('play.google.com')]
                latest['vercode'].fillna("0", inplace=True)
                latest['app'] = latest.pkg_name + "--" + latest.vercode
                if s_type == FrontmatterSourceType.apk:
                    latest = latest[latest.app.isin(apps)]
                elif s_type == FrontmatterSourceType.sha:
                    latest = latest[latest.sha256.isin(apps)]
                latest = latest[['app', 'sha256']]
                latest.to_csv(sha_file, index=False, header=False)
                lock_file.unlink(missing_ok=True)
            else:
                waiting.wait(lambda: not lock_file.exists(), timeout_seconds=60)
        with sha_file.open() as fd:
            return dict(line.strip().split(',') for line in fd.readlines())


# add selection
class SimpleZooCrawler:
    ZOO_BASE_URL = 'https://androzoo.uni.lu/api/download'

    def __init__(self, zoo_api_key: str):
        self.ZOO_API_KEY = zoo_api_key

    def download_full_apk(self, sha256, dest_fd):
        with requests.get(self.ZOO_BASE_URL, {'apikey': self.ZOO_API_KEY, 'sha256': sha256}, stream=True, timeout=5) as response:
            for chunk in response.iter_content(chunk_size=100 * 1024):
                if chunk:
                    dest_fd.write(chunk)


class FrontmatterWorker(luigi.Task, metaclass=ABCMeta):
    apk_name: str = luigi.Parameter()
    log_folder: str = luigi.Parameter(significant=False)
    output_folder: str = luigi.Parameter(significant=False)
    sha: str = luigi.Parameter(significant=False)

    @property
    @abc.abstractmethod
    def cmd_args(self):
        raise NotImplementedError("cmd_args not defined")

    def requires(self):
        apk_path = str(config.get_apk_path(self.apk_name))
        if config.source == 'local':
            return ApkFile(apk_file=apk_path)
        elif config.source == 'zoo':
            return DownloadZooApk(apk_name=self.apk_name, sha256=self.sha)
        else:
            raise ValueError(f'Unknown source {config.source}')

    def output(self):
        pkg = config.get_pkg(self.apk_name)
        output_file = config.get_path(self.output_folder, pkg) / f"{self.apk_name}.json"
        return LocalTarget(output_file)

    def run(self):
        logger.info(f'Running Frontmatter on apk {self.apk_name}')
        self.pkg = config.get_pkg(self.apk_name)
        os.makedirs(self.log_folder, exist_ok=True)
        cmd_line = ' '.join(self.cmd_args)
        logger.debug(f'Running with: {cmd_line}')
        try:
            curr_time = time.time()
            log_file = config.get_path(self.log_folder, self.pkg) / f"{self.apk_name}.log"
            with log_file.open('wb') as log_fd:
                subprocess.run(self.cmd_args, stdout=log_fd, stderr=log_fd, timeout=config.timeout, check=True)
            time_delta = time.time() - curr_time
            time_file = config.get_path(self.log_folder, self.pkg) / f"{self.apk_name}.time"
            with time_file.open('w') as fd:
                fd.write(str(time_delta))
            # self.cleanup() //TODO: make cleanup
        except subprocess.TimeoutExpired:
            with self.output().open('w') as f:
                f.write('{"error":"TIMEOUT"}\n')
        except subprocess.CalledProcessError as e:
            Path(self.output().path).parent.mkdir(exist_ok=True)
            with self.output().open('w') as f:
                sout = {"error": "EXCEPTION", "code": e.returncode}
                json.dump(sout, f, indent=2)

    def cleanup(self):
        if config.delete_apk and config.source == 'zoo':
            os.remove(self.input().path)


class FrontmatterUIWorker(FrontmatterWorker):
    detect_lang: bool = luigi.BoolParameter(significant=False, default=True)
    with_transitions: bool = luigi.BoolParameter(significant=False, default=True)

    @property
    def cmd_args(self):
        cmd_line = [
            "java",
            f"-Xmx{config.xmx}",
            f"-Xss{config.xss}",
            "-XX:-UseGCOverheadLimit",
            "-jar", config.frontmatter_path,
            "ui-analysis",
            "--android", config.android_platform,
            "-i", self.input().path,
            "--ui", self.output().path,
            "--boomerang-timeout", str(config.resolver_timeout),
        ]
        if self.detect_lang:
            cmd_line += ["--detect-lang"]
        if self.with_transitions:
            cmd_line += ["-t"]
        return cmd_line


class FrontmatterAPIWorker(FrontmatterWorker):
    ui_folder: str = luigi.Parameter()

    @property
    def cmd_args(self):
        ui_file = config.get_path(self.ui_folder, self.pkg) / f"{self.apk_name}.json"
        return [
            "java",
            f"-Xmx{config.xmx}",
            f"-Xss{config.xss}",
            "-XX:-UseGCOverheadLimit",
            "-jar", config.frontmatter_path,
            "api-analysis",
            "--android", config.android_platform,
            "-i", self.input().path,
            "--ui", str(ui_file),
            "--api", self.output().path,
        ]


class FrontmatterCollectAPIWorker(FrontmatterWorker):
    without_args = luigi.BoolParameter()

    @property
    def cmd_args(self):
        cmd_line = [
            "java",
            f"-Xmx{config.xmx}",
            f"-Xss{config.xss}",
            "-XX:-UseGCOverheadLimit",
            "-jar", config.frontmatter_path,
            "collect-api",
            "--android", config.android_platform,
            "-i", self.input().path,
            "--api", self.output().path,
        ]
        if self.without_args:
            cmd_line += ['--without-args']
        return cmd_line


# "-XX:ReservedCodeCacheSize=512m",
# "-XX:ParallelCMSThreads=4","-XX:UseConcMarkSweepGC"
# "-XX:ParallelGCThreads=4",
# "-XX:CICompilerCount=6",


class DownloadZooApk(luigi.Task):
    apk_name: str = luigi.Parameter()
    sha256: str = luigi.Parameter()
    api_key: str = luigi.Parameter()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_crawler = SimpleZooCrawler(self.api_key)

    def output(self):
        apk_path = config.get_apk_path(self.apk_name)
        return LocalTarget(apk_path, format=luigi.format.Nop)

    def run(self):
        with self.output().open('wb') as fd:
            self.apk_crawler.download_full_apk(self.sha256, fd)


class ApkFile(luigi.ExternalTask):
    apk_file: str = luigi.Parameter()

    def output(self):
        return luigi.LocalTarget(self.apk_file)


class FrontmatterAnalysis(luigi.WrapperTask, metaclass=ABCMeta):
    list_file: str = luigi.Parameter(significant=False)
    s_type: FrontmatterSourceType = luigi.EnumParameter(enum=FrontmatterSourceType, significant=False, default=FrontmatterSourceType.apk)
    zoo_sha_cache: dict = {}

    def get_apps_from_list(self):
        with open(self.list_file) as fd:
            app_list = [x.strip() for x in fd.readlines()]
        return app_list

    def get_sha_from_list(self):
        with open(self.list_file) as fd:
            sha_list = [x.strip() for x in fd.readlines()]
        return sha_list

    def load_zoo(self, apps: list, s_type: FrontmatterSourceType):
        sha_file = Path(self.list_file).with_suffix('.sha')
        self.zoo_sha_cache = ZooLoaderTask(androzoo_csv=config.androzoo_csv, from_google_play=config.from_google_play).run(sha_file=sha_file, apps=apps, s_type=s_type)

    def get_sha(self, app):
        return self.zoo_sha_cache.get(app, '')

    def requires(self):
        self.apps = self.get_apps_from_list()
        if self.s_type == FrontmatterSourceType.apk:
            if config.source == 'zoo':
                self.load_zoo(self.apps, self.s_type)
        elif self.s_type == FrontmatterSourceType.sha:
            self.load_zoo(self.apps, self.s_type)
            self.apps = self.zoo_sha_cache.keys()
        else:
            raise ValueError(f"Unknown type {self.s_type}")

        for apk in self.apps:
            yield self.worker_cls(apk_name=apk, sha=self.get_sha(apk))

    @property
    @abc.abstractmethod
    def worker_cls(self):
        raise NotImplementedError("Subclass must override this method")


class FrontmatterUIAnalysis(FrontmatterAnalysis):
    @property
    def worker_cls(self):
        return FrontmatterUIWorker


class FrontmatterApiAnalysis(FrontmatterAnalysis):
    @property
    def worker_cls(self):
        return FrontmatterAPIWorker


class FrontmatterCollectApi(FrontmatterAnalysis):
    @property
    def worker_cls(self):
        return FrontmatterCollectAPIWorker


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--command", choices=['ui', 'api', 'collect-api'], help="choose one of the tasks to proceed with")
    known_args, luigi_args = parser.parse_known_args()

    if known_args.command == 'ui':
        luigi.run(main_task_cls=FrontmatterUIAnalysis, cmdline_args=luigi_args)
    elif known_args.command == 'api':
        luigi.run(main_task_cls=FrontmatterApiAnalysis, cmdline_args=luigi_args)
    elif known_args.command == 'collect-api':
        luigi.run(main_task_cls=FrontmatterCollectApi, cmdline_args=luigi_args)
    else:
        print(f"Unknown command: {known_args.command}")
