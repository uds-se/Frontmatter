import argparse
import json
import os
import random
import sys
import time

import luigi
import luigi.format
import pandas as pd
import requests
from androguard.core.bytecodes.axml import AXMLPrinter
from joblib import Parallel, delayed
from luigi import LocalTarget
from lxml import etree

from apkpure_crawler import ZipReader
from config import ZooConfig


class ZooCrawler:
    ZOO_BASE_URL = 'https://androzoo.uni.lu/api/download'
    ZOO_API_KEY = '***REMOVED***'
    ANDROZOO_CSV = 'latest.csv'
    apk_bytes = 100000
    zip_reader = ZipReader()

    def __init__(self):
        self.android6_release_date = pd.Timestamp(time.mktime(config.android_6_release_date), unit='s')
        self.undefined_date = pd.Timestamp(time.mktime(config.android_1_release_date), unit='s')

    def make_apk_versions(self):
        df = pd.read_csv('latest.csv')
        df['dex_date'] = pd.to_datetime(df['dex_date'])
        android6_df = df[(df['dex_date'] > self.android6_release_date) | (df['dex_date'] < self.undefined_date)]
        android6_gp_df = android6_df[android6_df['markets'].str.contains('play.google.com')]
        multiple_versions = android6_gp_df[android6_gp_df.duplicated('pkg_name', keep=False)]
        grouped_versions = multiple_versions.groupby('pkg_name')
        for pkg, df_pkg in grouped_versions:
            pkg_file = config.versions_list.format(app=pkg)
            pkg_list = []
            for index, row in df_pkg.iterrows():
                pkg_list.append({'sha256': row['sha256'], 'ver_code': row['vercode']})
            with open(pkg_file, 'w') as fd:
                json.dump(pkg_list, fd)

    def get_apk_list(self):
        pass

    def download_part_apk(self, sha256):
        response = requests.get(self.ZOO_BASE_URL, {'apikey': self.ZOO_API_KEY, 'sha256': sha256}, stream=True)
        stream = response.iter_content(self.apk_bytes)
        data = next(stream)
        stream.close()
        return data

    def download_full_apk(self, sha256, dest_fd):
        with requests.get(self.ZOO_BASE_URL, {'apikey': self.ZOO_API_KEY, 'sha256': sha256}, stream=True) as response:
            for chunk in response.iter_content(chunk_size=100 * 1024):
                if chunk:
                    dest_fd.write(chunk)

    @staticmethod
    def _extract_android_manifest(manifest_data):
        if manifest_data is None:
            # print("Error reading apk - AndroidManifest.xml not found")
            return None
        ap = AXMLPrinter(manifest_data)
        if not ap.is_valid():
            # print("Error while parsing AndroidManifest.xml - is the file valid?")
            return None
        res = ap.get_xml_obj()
        # from lxml import etree
        # etree.tostring(self.root, encoding="utf-8", pretty_print=pretty)
        return res

    def get_android_manifest(self, data):
        manifest_data = self.zip_reader.read_manifest_from_zip(data, raw=True)
        return self._extract_android_manifest(manifest_data)


class LocalFile(luigi.ExternalTask):
    file_name: str = luigi.Parameter()

    def output(self):
        return luigi.LocalTarget(self.file_name)


class ZooVersionsRun(luigi.Task):

    def requires(self):
        return LocalFile('latest.csv')

    def output(self):
        return LocalTarget(config.versions_done)

    def run(self):
        zc = ZooCrawler()
        zc.make_apk_versions()
        with self.output().open('w') as fd:
            fd.write('done')


class ZooDownloadPerPkgRun(luigi.Task):
    versions_file: str = luigi.Parameter()

    def requires(self):
        return LocalFile(os.path.join(config.versions_dir, self.versions_file))

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.zoo_crawler = ZooCrawler()
        self.pkg = os.path.splitext(self.versions_file)[0]

    def output(self):
        return LocalTarget(config.per_version_download_done.format(pkg=self.pkg))

    def download(self, sha, ver_code):
        file_name = f"{self.pkg}--{ver_code}.xml"
        out_file = config.apk.format(pkg=self.pkg, apk=file_name)
        if os.path.exists(out_file):
            return
        dir_name = os.path.dirname(out_file)
        if not os.path.exists(dir_name):
            os.mkdir(dir_name)
        fail_count = 0
        apk_data = None
        while fail_count < 3:
            try:
                apk_data = self.zoo_crawler.download_part_apk(sha)
                break
            except requests.exceptions.ConnectionError as ex:
                fail_count += 1
                time.sleep(random.randrange(1, 30))
        if apk_data is None:
            raise Exception("Failed to download")
        manifest = self.zoo_crawler.get_android_manifest(apk_data)
        if manifest is not None:
            manifest_xml = etree.tostring(manifest, encoding="utf-8", pretty_print=True)
        else:
            manifest_xml = b'''<?xml version="1.0" ?>
                            <error>NOT_FOUND</error>
                            '''
        with open(out_file, 'wb') as fd:
            fd.write(manifest_xml)

    def run(self):
        with self.input().open() as fd:
            versions = json.load(fd)
            for version in versions:
                # date = time.strptime(version['date'], "%Y-%m-%d")
                # if date > config.android_6_release_date:
                sha = version['sha256']
                ver_code = int(version['ver_code'])
                self.download(sha, ver_code)
        with self.output().open('w') as fd:
            fd.write('done')


class ZooDownloadAppsPerPkgRun(luigi.WrapperTask):

    def requires(self):
        for file_name in os.listdir(config.versions_dir):
            if not file_name.endswith('json'):
                continue
            yield ZooDownloadPerPkgRun(versions_file=file_name)


class DownloadZooApk(luigi.Task):
    pkg: str = luigi.Parameter()
    ver_code: str = luigi.Parameter()

    def requires(self):
        return LocalFile(config.app_versions_list.format(pkg=self.pkg))

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_crawler = ZooCrawler()

    def output(self):
        return LocalTarget(config.downloaded_apk_file.format(pkg=self.pkg, ver=self.ver_code), format=luigi.format.Nop)

    def download(self, sha256):
        out_file = self.output().path
        if os.path.exists(out_file):
            return
        with self.output().open('wb') as fd:
            self.apk_crawler.download_full_apk(sha256, fd)

    def run(self):
        with self.input().open() as fd:
            versions = json.load(fd)
        version_item = None
        for version in versions:
            if int(version['ver_code']) == int(self.ver_code):
                version_item = version
                break
        version_sha256 = version_item['sha256']
        self.download(version_sha256)


class DownloadZooApks(luigi.WrapperTask):
    autogranted_file: str = luigi.Parameter()

    def requires(self):
        with open(self.autogranted_file) as fd:
            autogranted_cases = json.load(fd)
        for item in autogranted_cases:
            pkg = item['pkg']
            ver_code = item['version']
            yield DownloadZooApk(pkg=pkg, ver_code=ver_code)


class ZooDownloadJoblib:

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.zoo_crawler = ZooCrawler()

    def download(self, sha, ver_code, pkg):
        file_name = f"{pkg}--{ver_code}.xml"
        out_file = config.apk.format(pkg=pkg, apk=file_name)
        if os.path.exists(out_file):
            return
        dir_name = os.path.dirname(out_file)
        if not os.path.exists(dir_name):
            os.mkdir(dir_name)
        fail_count = 0
        apk_data = None
        while fail_count < 3:
            try:
                apk_data = self.zoo_crawler.download_part_apk(sha)
                break
            except requests.exceptions.ConnectionError as ex:
                fail_count += 1
                time.sleep(random.randrange(1, 30))
        if apk_data is None:
            raise Exception("Failed to download")
        try:
            manifest = self.zoo_crawler.get_android_manifest(apk_data)
        except:
            manifest = None
        if manifest is not None:
            manifest_xml = etree.tostring(manifest, encoding="utf-8", pretty_print=True)
        else:
            manifest_xml = b'''<?xml version="1.0" ?>
                              <error>NOT_FOUND</error>
                            '''
        with open(out_file, 'wb') as fd:
            fd.write(manifest_xml)

    def process_pkg(self, versions_file):
        pkg = os.path.splitext(versions_file)[0]
        file_name = os.path.join(config.versions_dir, versions_file)
        with open(file_name) as fd:
            versions = json.load(fd)
            for version in versions:
                sha = version['sha256']
                ver_code = int(version['ver_code'])
                self.download(sha, ver_code, pkg)

    def run(self, n_jobs):
        pkg_list = [file_name for file_name in os.listdir(config.versions_dir) if file_name.endswith('json')]
        print(len(pkg_list))
        tasks = Parallel(n_jobs=n_jobs)(delayed(self.process_pkg)(file_name) for file_name in pkg_list)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=['versions', 'download_manifest', 'download_apk', 'download_apk_bulk'], help="choose one of the tasks to proceed with")
    parser.add_argument("--jobs", type=int, help="Number of jobs")
    known_args, unknown_args = parser.parse_known_args()
    config = ZooConfig()
    if known_args.command == 'versions':
        luigi.run(main_task_cls=ZooVersionsRun, cmdline_args=sys.argv[2:])
    if known_args.command == 'download_apk':
        luigi.run(main_task_cls=DownloadZooApk, cmdline_args=sys.argv[2:])
    if known_args.command == 'download_apk_bulk':
        luigi.run(main_task_cls=DownloadZooApks, cmdline_args=sys.argv[2:])
    if known_args.command == 'download_manifest':  # in parallel
        zl = ZooDownloadJoblib()
        zl.run(known_args.jobs)
    # if known_args.command == 'download':
    #     luigi.run(main_task_cls=DownloadRun, cmdline_args=sys.argv[2:])
    if known_args.command == 'download_bulk':  # manifest with luigi, too slow
        luigi.run(main_task_cls=ZooDownloadAppsPerPkgRun, cmdline_args=sys.argv[2:])
    if known_args.command == 'download_pkg':  # manifest with luigi
        luigi.run(main_task_cls=ZooDownloadPerPkgRun, cmdline_args=sys.argv[2:])
