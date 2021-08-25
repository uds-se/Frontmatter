# https://github.com/TheZ3ro/zipfix/blob/master/zipfix.py
import argparse
import json
import os
import random
import re
import struct
import time
import zipfile
from collections import defaultdict
from io import BytesIO
from pathlib import Path

import langid
import luigi
import luigi.format
import requests
import tqdm
from androguard.core.bytecodes.axml import AXMLPrinter
from bs4 import BeautifulSoup
from googletrans import Translator
from luigi import LocalTarget
from lxml import etree
from urllib3.exceptions import ProtocolError

from config import ApkpureConfig, ApkpureGamesConfig


class ZipReader:
    """
    reads corrupted zip (apk) file
    """
    structDataDescriptor = b"<4sL2L"
    stringDataDescriptor = b"PK\x07\x08"
    sizeDataDescriptor = struct.calcsize(structDataDescriptor)
    _DD_SIGNATURE = 0
    _DD_CRC = 1
    _DD_COMPRESSED_SIZE = 2
    _DD_UNCOMPRESSED_SIZE = 3

    def fdescriptor_reader(self, file, initial_offset=zipfile.sizeFileHeader):
        offset = initial_offset
        file.seek(offset)
        while True:
            temp = file.read(1024)
            if len(temp) < 1024:
                print('Found end of file.  Some entries missed.')
                return None

            parts = temp.split(self.stringDataDescriptor)
            if len(parts) > 1:
                offset += len(parts[0])
                break
            else:
                offset += 1024
        file.seek(offset)

        ddescriptor = file.read(self.sizeDataDescriptor)
        if len(ddescriptor) < self.sizeDataDescriptor:
            print('Found end of file.  Some entries missed.')
            return None

        ddescriptor = struct.unpack(self.structDataDescriptor, ddescriptor)
        if ddescriptor[self._DD_SIGNATURE] != self.stringDataDescriptor:
            print('Error reading data descriptor.')
            return None

        file.seek(initial_offset)
        return ddescriptor

    def read_manifest_from_zip(self, filename, raw=False):
        if raw:
            content = bytearray(filename)
        else:
            with open(filename, 'rb') as fd:
                content = fd.read()
        try:
            with BytesIO(content) as f:
                while True:
                    # Read and parse a file header
                    fheader = f.read(zipfile.sizeFileHeader)
                    if len(fheader) < zipfile.sizeFileHeader:
                        print('Found end of file.  Some entries missed.')
                        return None
                    fheader = struct.unpack(zipfile.structFileHeader, fheader)
                    if fheader[zipfile._FH_SIGNATURE] == zipfile.stringCentralDir:
                        print('Found start of central directory.  All entries processed.')
                        return None
                    # if fheader[zipfile._FH_SIGNATURE] != zipfile.stringFileHeader:
                    #     raise Exception('Size mismatch! File Header expected, got "%s"' % (fheader[zipfile._FH_SIGNATURE]))
                    fname = f.read(fheader[zipfile._FH_FILENAME_LENGTH])
                    if fheader[zipfile._FH_EXTRA_FIELD_LENGTH]:
                        f.read(fheader[zipfile._FH_EXTRA_FIELD_LENGTH])
                    print('Found %s' % fname.decode())

                    # Fake a zipinfo record
                    zi = zipfile.ZipInfo()
                    zi.filename = fname
                    zi.compress_size = fheader[zipfile._FH_COMPRESSED_SIZE]
                    zi.compress_type = fheader[zipfile._FH_COMPRESSION_METHOD]
                    zi.flag_bits = fheader[zipfile._FH_GENERAL_PURPOSE_FLAG_BITS]
                    zi.file_size = fheader[zipfile._FH_UNCOMPRESSED_SIZE]
                    zi.CRC = fheader[zipfile._FH_CRC]
                    is_data_descriptor = fheader[zipfile._FH_GENERAL_PURPOSE_FLAG_BITS] & 0x8
                    if is_data_descriptor:
                        # Compress size is zero. Get the real sizes with data descriptor
                        data_descriptor = self.fdescriptor_reader(f, f.tell())
                        if data_descriptor is None:
                            return None
                        zi.compress_size = data_descriptor[self._DD_COMPRESSED_SIZE]
                        zi.file_size = data_descriptor[self._DD_UNCOMPRESSED_SIZE]
                        zi.CRC = data_descriptor[self._DD_CRC]
                    # Read the file contents
                    zef = zipfile.ZipExtFile(f, 'rb', zi)
                    data = zef.read()

                    # Sanity checks
                    if len(data) != zi.file_size:
                        return None
                        # raise Exception("Unzipped data doesn't match expected size! %d != %d, in %s" % (len(data), zi.file_size, fname))
                    calc_crc = zipfile.crc32(data) & 0xffffffff
                    if calc_crc != zi.CRC:
                        return None
                        # raise Exception('CRC mismatch! %d != %d, in %s' % (calc_crc, zi.CRC, fname))
                    if fname == b"AndroidManifest.xml":
                        return data
                    if is_data_descriptor:
                        f.seek(f.tell() + self.sizeDataDescriptor)  # skip dataDescriptor before reading the next file
        except:
            return None


config = ApkpureConfig()  # by default use ApkpureConfig


class ApkPureCrawler:
    base_url = 'https://apkpure.com'
    whats_new_url = f'{base_url}/api/www/command-version_more'
    zip_reader = ZipReader()
    apk_bytes = 100000

    def __init__(self):
        self.category_url = config.category_url.format(base_url=self.base_url)

    def get_categories(self):
        response = requests.get(self.category_url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        category_index = config.category_index
        categories_div = soup.find_all("ul", {"class": "index-category cicon"})[category_index]
        categories = [c['href'] for c in categories_div.find_all("a")]
        return categories

    def get_apps_of_category(self, category, page=1):
        category = category.strip('/')
        url = f'{self.base_url}/{category}?page={page}'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        page_data = soup.find("ul", {"id": "pagedata"})
        if page_data is None:
            return []
        app_links = list({l['href'] for l in page_data.find_all("a") if "/download?from" not in l['href']})
        return app_links

    def get_all_apps_of_category(self, category):
        page_id = 1
        res = []
        while True:
            chunk = self.get_apps_of_category(category, page_id)
            res.extend(chunk)
            if len(chunk) == 0:
                break
            page_id += 1
        return res

    @staticmethod
    def get_vercode_from_info(info):
        try:
            version_string = info.find("div", {"class": "ver-info-top"}).text
            search = re.search(r"\((?P<vercode>[0-9]+)\)$", version_string)
            vercode = search['vercode'] if search else None
            return vercode
        except:
            return None

    @staticmethod
    def get_vercode_from_link(link):
        try:
            search = re.search(r"download/(?P<vercode>[0-9]+)-", link)
            vercode = search['vercode'] if search else None
            return vercode
        except:
            return None

    @staticmethod
    def is_download_link(link):
        return '/download/' in link

    @staticmethod
    def is_variant_link(link):
        return '/variant/' in link

    @staticmethod
    def _get_value_from_info(info, value):
        try:
            info_container = info.find("div", {"class": "ver-info-m"})
            for item in info_container.find_all("p"):
                key = item.find("strong")
                if key and value in key.text:
                    return item.text.replace(value, "").strip()
        except:
            pass
        return None

    def get_sha_from_info(self, info):
        return self._get_value_from_info(info, 'File SHA1:')

    def get_date_from_info(self, info):
        return self._get_value_from_info(info, 'Update on:')

    @staticmethod
    def get_vername(version_container):
        vername = version_container.find("span", {"class": "ver-item-n"})
        return vername.text if vername else ''

    def get_variants(self, app_link):
        url = f'{self.base_url}{app_link}'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        variants_table = soup.find("div", {"class": "table"})
        variants = []
        ver_name = soup.select_one("div[class='variant'] > div[class='tit'] > span").text
        whats_new_element = soup.find("div", {"class": "whatsnew"})
        whats_new = whats_new_element.get_text(separator='\n') if whats_new_element else ""
        for variant in variants_table.select("div[class='table-row']"):
            link = variant.select_one("div[class='table-cell down'] > a")["href"]
            info = variant.find("div", {"class": "ver-info"})
            ver_code = self.get_vercode_from_info(info)
            date = self.get_date_from_info(info)
            sha = self.get_sha_from_info(info)
            variants.append({"link": link, "ver_code": ver_code, "ver_name": ver_name, "date": date, "sha1": sha, "whats_new": whats_new})
        return variants

    def get_versions(self, app_link):
        url = f'{self.base_url}{app_link}/versions'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        versions_container = soup.find("div", {"class": "ver"})
        if versions_container is None:
            return []
        versions = []
        for version_container in versions_container.find_all("li"):
            try:
                link = version_container.find("a")["href"]
                info = version_container.find("div", {"class": "ver-info"})
                if info is not None:
                    ver_code = self.get_vercode_from_info(info)
                    date = self.get_date_from_info(info)
                    sha = self.get_sha_from_info(info)
                    ver_name = self.get_vername(version_container)
                    whats_new = self._get_whats_new_text(version_container)
                    versions.append({"link": link, "ver_code": ver_code, "ver_name": ver_name, "date": date, "sha1": sha, "whats_new": whats_new})
                else:
                    if self.is_variant_link(link):
                        variants = self.get_variants(link)
                        versions.extend(variants)
            except Exception as e:
                pass
        return versions

    def get_download_link(self, apk_link):
        url = f'{self.base_url}{apk_link}'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        download_link = soup.find("a", {"id": "download_link"})['href']
        return download_link
        # file_name = self.get_file_name(apk_link)

    @staticmethod
    def get_pkg_version(version_link):
        try:
            pkg_ver = re.search("/(?P<pkg>[^/]+)/download/(?P<ver>[0-9]+)-X?APK", version_link)
            pkg, ver = pkg_ver['pkg'], pkg_ver['ver']
            return pkg, ver
        except:
            print(version_link)
            raise Exception

    def get_pkg(self, version_link):
        pkg, _ = self.get_pkg_version(version_link)
        return pkg

    def get_apk_name(self, version_link):
        pkg, ver = self.get_pkg_version(version_link)
        file_name = f"{pkg}--{ver}"
        return file_name

    def download_part_apk(self, url):
        fail_count = 0
        while fail_count < 3:
            try:
                response = requests.get(url, stream=True)
                stream = response.iter_content(self.apk_bytes)
                data = next(stream)
                stream.close()
                return data
            except (ProtocolError, requests.exceptions.ConnectionError) as ex:
                fail_count += 1
                time.sleep(random.randrange(1, 30))
        return None

    @staticmethod
    def download_full_apk(url, dest_fd):
        fail_count = 0
        while fail_count < 3:
            try:
                with requests.get(url, stream=True) as response:
                    for chunk in response.iter_content(chunk_size=100 * 1024):
                        if chunk:
                            dest_fd.write(chunk)
                    # shutil.copyfileobj(response.raw, dest_fd)
                return 0
            except (ProtocolError, requests.exceptions.ConnectionError) as ex:
                fail_count += 1
                time.sleep(random.randrange(1, 30))
        return None

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

    def get_android_manifest_from_file(self, file_path):
        manifest_data = self.zip_reader.read_manifest_from_zip(file_path)
        return self._extract_android_manifest(manifest_data)

    def get_description(self, apk_link):
        url = f'{self.base_url}{apk_link}'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        description = soup.find("div", {"class": "content", "itemprop": "description"})
        return description.get_text(separator='\n')

    def request_whats_new_text(self, pkg, vid, lang):
        data = {'package': pkg, 'vid': vid, 'lang': lang}
        response = requests.post(self.whats_new_url, data)
        content = response.json()
        if content['error'] == 0:
            return content['doc'].replace('<br>', '\n')
        else:
            return ""

    def get_whats_new_from_variants(self, app_link):
        url = f'{self.base_url}{app_link}'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        whats_new = soup.find("div", {"class": "whatsnew"})
        variants_table = soup.find("div", {"class": "table"})
        ver_codes = []
        for variant in variants_table.select("div[class='table-row']"):
            info = variant.find("div", {"class": "ver-info"})
            ver_code = self.get_vercode_from_info(info)
            ver_codes.append(ver_code)
        whats_new_text = whats_new.get_text(separator='\n') if whats_new is not None else ""
        return ver_codes, whats_new_text

    def _get_whats_new_text(self, version_container):
        version_item = version_container.find("i", {"class": "ver-item-m pop"})
        if version_item is None:
            return ''
        data_p = version_item['data-p']
        data_vid = version_item['data-vid'].lstrip('v')
        data_lang = version_item['data-lang']
        return self.request_whats_new_text(data_p, data_vid, data_lang)

    def get_whats_new(self, app_link, versions):
        url = f'{self.base_url}{app_link}/versions'
        response = requests.get(url)
        content = response.text.replace("&#8203", "")
        soup = BeautifulSoup(content, "html.parser")
        versions_container = soup.find("div", {"class": "ver"})
        if versions_container is None:
            return []
        whats_new_data = []
        variants_worklist = []
        processed = set()
        for version_container in versions_container.find_all("li"):
            try:
                link = version_container.find("a")["href"]
                if self.is_variant_link(link):
                    variants_worklist.append(link)
                if self.is_download_link(link):
                    ver_code = self.get_vercode_from_link(link)
                    if ver_code in versions:
                        processed.add(ver_code)
                        whats_new_data.append({'version': ver_code, 'text': self._get_whats_new_text(version_container)})
            except:
                pass
        # process variants - we don't know its ver_code beforehand
        to_process = set(versions) - processed
        for variant_link in variants_worklist:
            if len(to_process) == 0:
                break
            ver_codes, whats_new_text = self.get_whats_new_from_variants(variant_link)
            for ver_code in ver_codes:
                if ver_code in to_process:
                    to_process.remove(ver_code)
                    whats_new_data.append({'version': ver_code, 'text': whats_new_text})
        return whats_new_data


class LocalFile(luigi.ExternalTask):
    file_name: str = luigi.Parameter()

    def output(self):
        return luigi.LocalTarget(self.file_name)


class CategoriesRun(luigi.Task):
    """
    download list of apkpure categories, store into categories_list
    """

    def output(self):
        return LocalTarget(config.categories_list)

    def run(self):
        apk_crawler = ApkPureCrawler()
        categories = apk_crawler.get_categories()
        with self.output().open('w') as fd:
            fd.writelines([f"{c}\n" for c in categories])


class ApplicationRun(luigi.Task):
    """
    download all links of apps from a category, store into app_category_list
    """
    category: str = luigi.Parameter()

    def requires(self):
        return CategoriesRun()

    def output(self):
        app_list = config.app_category_list.format(category=self.category.strip('/'))
        return LocalTarget(app_list)

    def run(self):
        apk_crawler = ApkPureCrawler()
        categories = apk_crawler.get_all_apps_of_category(self.category)
        with self.output().open('w') as fd:
            fd.writelines([f"{c}\n" for c in categories])


class ApplicationsRun(luigi.Task):
    """
    download all links of apps from each category, using ApplicationRun, store in app_list
    """

    def requires(self):
        return CategoriesRun()

    def output(self):
        return LocalTarget(config.app_list)

    def run(self):
        with self.input().open() as fd:
            categories = [l.strip('\n') for l in fd.readlines()]
        outputs = []
        tasks = []
        for category in categories:
            task = ApplicationRun(category=category)
            tasks.append(task)
            outputs.append(task.output().path)
        yield tasks
        with self.output().open('w') as fd:
            for path in outputs:
                with open(path) as f:
                    fd.write(f.read())


class VersionRun(luigi.Task):
    """
    download all versions of an app given @version_link, store into app_versions_list
    """
    version_link: str = luigi.Parameter()

    def output(self):
        pkg = self.version_link.split('/')[-1]
        version_list = config.app_versions_list.format(pkg=pkg)
        return LocalTarget(version_list)

    def run(self):
        apk_crawler = ApkPureCrawler()
        versions = apk_crawler.get_versions(self.version_link)
        with self.output().open('w') as fd:
            json.dump(versions, fd, indent=1)


class VersionsRun(luigi.Task):
    file_name: str = luigi.Parameter(default="", significant=False)
    """
    download all versions of each app using VersionRun, given all links
    kinda Wrapper
    """

    def requires(self):
        if self.file_name == "":
            return ApplicationsRun()
        else:
            return LocalFile(file_name=self.file_name)

    def output(self):
        return LocalTarget(config.versions_done)

    def run(self):
        with self.input().open() as fd:
            version_links = [l.strip('\n') for l in fd.readlines()]
        tasks = []
        for version_link in version_links:
            task = VersionRun(version_link=version_link)
            tasks.append(task)
        yield tasks
        with self.output().open('w') as fd:
            fd.write('done')
            # for path in outputs:
            #     with open(path) as f:
            #         fd.write(f.read())


class DownloadManifestRun(luigi.Task):
    url: str = luigi.Parameter()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_crawler = ApkPureCrawler()
        self.file_name = self.apk_crawler.get_apk_name(self.url) + '.xml'
        self.pkg = self.apk_crawler.get_pkg(self.url)

    def output(self):
        return LocalTarget(config.manifest_file.format(pkg=self.pkg, apk=self.file_name), format=luigi.format.Nop)

    def run(self):
        download_link = self.apk_crawler.get_download_link(self.url)
        apk_data = self.apk_crawler.download_part_apk(download_link)
        manifest = self.apk_crawler.get_android_manifest(apk_data)
        if manifest is not None:
            manifest_xml = etree.tostring(manifest, encoding="utf-8", pretty_print=True)
        else:
            manifest_xml = b'''<?xml version="1.0" ?>
                          <error>NOT_FOUND</error>
                        '''
        with self.output().open('w') as fd:
            fd.write(manifest_xml)


class DownloadManifestPerPkgRun(luigi.Task):
    versions_file: str = luigi.Parameter()

    def requires(self):
        return LocalFile(os.path.join(config.app_versions_dir, self.versions_file))

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_crawler = ApkPureCrawler()
        self.pkg = os.path.splitext(self.versions_file)[0]

    def output(self):
        return LocalTarget(config.per_version_download_done.format(pkg=self.pkg))

    def download(self, url):
        file_name = self.apk_crawler.get_apk_name(url) + '.xml'
        out_file = config.manifest_file.format(pkg=self.pkg, apk=file_name)
        out_dir = os.path.dirname(out_file)
        os.makedirs(out_dir, exist_ok=True)
        if os.path.exists(out_file):
            return
        try:
            download_link = self.apk_crawler.get_download_link(url)
        except:
            manifest_xml = b'''<?xml version="1.0" ?>
                                  <error>DOWNLOAD_LINK_ERROR</error>
                                '''
        else:
            apk_data = self.apk_crawler.download_part_apk(download_link)
            if apk_data is None:
                raise Exception("Failed to download")
            manifest = self.apk_crawler.get_android_manifest(apk_data)
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
                date = time.strptime(version['date'], "%Y-%m-%d")
                if date > config.android_6_release_date:
                    url = version['link']
                    self.download(url)
        with self.output().open('w') as fd:
            fd.write('done')


class DownloadManifestsPerPkgRun(luigi.WrapperTask):

    def requires(self):
        for file_name in os.listdir(config.app_versions_dir):
            if not file_name.endswith('json'):
                continue
            yield DownloadManifestPerPkgRun(versions_file=file_name)


class DownloadApkRun(luigi.Task):
    pkg: str = luigi.Parameter()
    ver_code: str = luigi.Parameter()

    def requires(self):
        return LocalFile(config.app_versions_list.format(pkg=self.pkg))

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.apk_crawler = ApkPureCrawler()

    def output(self):
        return LocalTarget(config.downloaded_apk_file.format(pkg=self.pkg, ver=self.ver_code), format=luigi.format.Nop)

    def download(self, url):
        out_file = self.output().path
        if os.path.exists(out_file):
            return
        download_link = self.apk_crawler.get_download_link(url)
        with self.output().open('wb') as fd:
            apk_data = self.apk_crawler.download_full_apk(download_link, fd)
        if apk_data is None:
            raise Exception("Failed to download")

    def run(self):
        with self.input().open() as fd:
            versions = json.load(fd)
        version_item = None
        for version in versions:
            if version['ver_code'] == self.ver_code:
                version_item = version
                break
        version_link = version_item['link']
        self.download(version_link)


class DownloadApksRun(luigi.WrapperTask):
    data_file: str = luigi.Parameter()

    def get_pkg_from_name(self):
        return Path(self.data_file).stem

    def requires(self):
        with open(self.data_file) as fd:
            items = json.load(fd)
        for item in items:
            pkg = item['pkg']
            ver_code = item['ver_code']
            yield DownloadApkRun(pkg=pkg, ver_code=ver_code)


class TextTranslator:
    def __init__(self):
        self.translator = Translator()

    def translate(self, text):
        fail_count = 0
        while fail_count < 3:
            try:
                res = self.translator.translate(text)
                return res.text
            except:
                fail_count += 1
                time.sleep(random.randrange(1, 30))
        return ''

    @staticmethod
    def get_language(text):
        res = langid.classify(text)
        return res[0]


class DescriptionAllRun:

    def __init__(self):
        self.apk_crawler = ApkPureCrawler()
        self.translator = TextTranslator()

    @staticmethod
    def get_pkg_to_link():
        app_to_link = dict()
        with open(config.app_list) as fd:
            lines = [l.strip('\n') for l in fd.readlines()]
        for line in lines:
            app_to_link[line.split('/')[-1]] = line
        return app_to_link

    def translate_description(self, text):
        self.translator.translate(text)

    def run(self, autogranted_file):
        if os.path.exists(config.description_file):
            with open(config.description_file) as fd:
                done_descriptions = json.load(fd)
        else:
            done_descriptions = dict()
        with open(autogranted_file) as fd:
            autogranted_list = json.load(fd)
        pkg_to_link = self.get_pkg_to_link()
        descriptions = dict()
        pkgs = {item['pkg'] for item in autogranted_list}
        for pkg in tqdm.tqdm(pkgs):
            if pkg in done_descriptions:
                continue
            link = pkg_to_link[pkg]
            description = self.apk_crawler.get_description(link)
            if description is None:
                print(f"Error: empty description for : {pkg}")
                continue
            lang = self.translator.get_language(description)
            translated_description = self.translate_description(description) if lang != 'en' else ''
            descriptions[pkg] = {'description': description, 'lang': lang, 'translated': translated_description}
        with open(config.description_file, 'w', encoding='utf8') as fd:
            fd.write(json.dumps(descriptions, ensure_ascii=False, indent=2))
        with open(config.description_plain_file, 'w') as fd:
            for pkg, item in descriptions.items():
                fd.write(f'{pkg}\n')
                fd.write(f'>>\n')
                if item['translated']:
                    fd.write(f"{item['translated']}\n")
                else:
                    fd.write(f"{item['description']}\n")
                fd.write(f"{''.join(['='] * 160)}\n")


class WhatsNewAllRun:

    def __init__(self):
        self.apk_crawler = ApkPureCrawler()
        self.translator = TextTranslator()

    @staticmethod
    def get_pkg_to_link():
        app_to_link = dict()
        with open(config.app_list) as fd:
            lines = [l.strip('\n') for l in fd.readlines()]
        for line in lines:
            app_to_link[line.split('/')[-1]] = line
        return app_to_link

    def run(self, autogranted_file):
        with open(autogranted_file) as fd:
            autogranted_list = json.load(fd)
        pkg_to_link = self.get_pkg_to_link()
        whats_new_data = dict()
        apks = defaultdict(list)
        for item in autogranted_list:
            apks[item['pkg']].append(item['version'])
        for pkg, versions in tqdm.tqdm(apks.items()):
            link = pkg_to_link[pkg]
            versions_whats_new = self.apk_crawler.get_whats_new(link, versions)
            if versions_whats_new is None:
                print(f"Error: what's new cannot be retrieved for {pkg}")
                continue
            for whats_new in versions_whats_new:
                ver_code = whats_new['version']
                whats_new_text = whats_new['text']
                whats_new_data[f'{pkg}--{ver_code}'] = whats_new_text
        with open(config.whatsnew_file, 'w', encoding='utf8') as fd:
            fd.write(json.dumps(whats_new_data, ensure_ascii=False, indent=2, sort_keys=True))
        self.dump_plain(whats_new_data)

    @staticmethod
    def dump_plain(whats_new_data):
        with open(config.whatsnew_plain_file, 'w') as fd:
            for pkg in sorted(whats_new_data):
                item = whats_new_data[pkg]
                fd.write(f'{pkg}\n')
                fd.write(f'>>\n')
                fd.write(f'{item}\n')
                fd.write(f"{''.join(['='] * 160)}\n")

    # @Deprecated - ERROR means no description available
    def fix_errors(self):
        with open(config.whatsnew_file) as fd:
            data = json.load(fd)
        pkg_to_link = self.get_pkg_to_link()
        fixed_data = dict()
        for apk, text in data.items():
            if text == 'ERROR':
                pkg, version = apk.split('--')
                link = pkg_to_link[pkg]
                versions_whats_new = self.apk_crawler.get_whats_new(link, [version])
                print(versions_whats_new)
                break

    def translate(self):
        with open(config.whatsnew_file) as fd:
            data = json.load(fd)
        translated_data = dict()
        for apk, text in tqdm.tqdm(data.items()):
            lang = self.translator.get_language(text)
            translated_text = self.translator.translate(text) if lang != 'en' else text
            translated_data[apk] = translated_text
        with open(config.whatsnew_file, 'w', encoding='utf8') as fd:
            json.dump(translated_data, fd, indent=2, sort_keys=True)
        self.dump_plain(translated_data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("command",
                        choices=['cat', 'app', 'apps', 'versions', 'download_apk', 'download_apk_bulk', 'download_manifest', 'download_manifest_bulk', 'download_manifest_pkg',
                                 'description', 'whatsnew', 'test'],
                        help="choose one of the tasks to proceed with")
    parser.add_argument("--file")
    parser.add_argument("--config", "-c")
    known_args, unknown_args = parser.parse_known_args()
    if known_args.config == 'apkpure':
        config = ApkpureConfig()
    elif known_args.config == 'games':
        config = ApkpureGamesConfig()
    else:
        raise ValueError("No config file defined")

    if known_args.command == 'cat':
        luigi.run(main_task_cls=CategoriesRun, cmdline_args=unknown_args)
    if known_args.command == 'app':
        luigi.run(main_task_cls=ApplicationRun, cmdline_args=unknown_args)
    if known_args.command == 'apps':
        luigi.run(main_task_cls=ApplicationsRun, cmdline_args=unknown_args)
    if known_args.command == 'versions':
        luigi.run(main_task_cls=VersionsRun, cmdline_args=unknown_args)
    if known_args.command == 'download_manifest':
        luigi.run(main_task_cls=DownloadManifestRun, cmdline_args=unknown_args)
    if known_args.command == 'download_manifest_bulk':
        luigi.run(main_task_cls=DownloadManifestsPerPkgRun, cmdline_args=unknown_args)
    if known_args.command == 'download_manifest_pkg':
        luigi.run(main_task_cls=DownloadManifestPerPkgRun, cmdline_args=unknown_args)
    if known_args.command == 'download_apk':
        luigi.run(main_task_cls=DownloadApkRun, cmdline_args=unknown_args)
    if known_args.command == 'download_apk_bulk':
        luigi.run(main_task_cls=DownloadApksRun, cmdline_args=unknown_args)
    if known_args.command == 'description':
        dr = DescriptionAllRun()
        dr.run(known_args.file)
    if known_args.command == 'whatsnew':
        wn = WhatsNewAllRun()
        # wn.run(known_args.file)
        wn.translate()
