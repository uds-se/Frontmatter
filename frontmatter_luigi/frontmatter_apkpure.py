import argparse
import csv
import json
import logging
import os
import re
import subprocess
from pathlib import Path

import luigi
import luigi.format
import time

import requests
from luigi import LocalTarget
from luigi.util import inherits
from abc import ABCMeta
from apkpure_crawler import DownloadApkRun, VersionRun
from frontmatter_luigi import FrontmatterConfig, FrontmatterWorker

logger = logging.getLogger('luigi-interface')


class ApkFile(luigi.ExternalTask):
    apk_file: str = luigi.Parameter()

    def output(self):
        return luigi.LocalTarget(self.apk_file)


class ApkPureAnalyseApk(luigi.Task):
    pkg: str = luigi.Parameter()
    ver_code: str = luigi.Parameter()
    res_folder: str = luigi.Parameter()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_name = FrontmatterConfig().get_apk_name(self.pkg, self.ver_code)

    def requires(self):
        return DownloadApkRun(pkg=self.pkg, ver_code=self.ver_code)

    def output(self):
        output_file_name = self.apk_name
        output_file = Path(self.res_folder) / (output_file_name + ".json")
        return luigi.LocalTarget(os.fspath(output_file))

    def run(self):
        worker = FrontmatterWorker()
        worker.run(self.apk_name, self.input().path, self.output().path)


class ApkPureAnalyseAppVersions(luigi.Task):
    app_link: str = luigi.Parameter()
    only_latest_version: bool = luigi.BoolParameter()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.is_complete = False

    @staticmethod
    def get_pkg_from_link(link):
        return link.split('/')[-1]

    def requires(self):
        return VersionRun(version_link=self.app_link)

    def run(self):
        with self.input().open() as fd:
            app_versions = json.load(fd)
        pkg = self.get_pkg_from_link(self.app_link)
        if self.only_latest_version:
            latest_version = app_versions[0]
            yield ApkPureAnalyseApk(pkg=pkg, ver_code=latest_version["ver_code"])
        else:
            for version in app_versions:
                yield ApkPureAnalyseApk(pkg=pkg, ver_code=version["ver_code"])
        self.is_complete = True

    def complete(self):
        return self.is_complete


class ApkPureAnalyseApps(luigi.WrapperTask):
    apps_list_file: str = luigi.Parameter()
    only_latest_version: bool = luigi.BoolParameter(default=True)

    def requires(self):
        with open(self.apps_list_file) as fd:
            for link in fd:
                link = link.strip()
                yield ApkPureAnalyseAppVersions(app_link=link, only_latest_version=self.only_latest_version)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--command", choices=['apkpure'], help="choose one of the tasks to proceed with")
    known_args, luigi_args = parser.parse_known_args()
    if known_args.command == 'apkpure':
        luigi.run(main_task_cls=ApkPureAnalyseApps, cmdline_args=luigi_args)
    else:
        print("Unknown command")
