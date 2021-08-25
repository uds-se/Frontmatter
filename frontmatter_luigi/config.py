import os
import time


class Config:
    data_folder = ''
    android_6_release_date = time.strptime('2015-10-05', "%Y-%m-%d")
    android_1_release_date = time.strptime('2008-09-23', "%Y-%m-%d")

    def p(self, *argv):
        return os.path.join(self.data_folder, *argv)

    def __init__(self):
        self.finder_path = "binary/soot-slicer-1.07-SNAPSHOT-all.jar"
        self.permission_mapping = "mapping/{permission}.txt"
        self.android_home = os.path.join(os.environ['ANDROID_HOME'], 'platforms')
        self.intents_file = "intents/{permission}.txt"
        self.timeout = '10m'
        self.code_location_prefix_size = 3


class ApkpureConfig(Config):
    category_url = '{base_url}/app'
    category_index = 1

    data_folder = 'data'

    def __init__(self):
        super().__init__()

        self.categories_list = self.p('apkpure_categories.list')
        self.app_category_list = self.p('categories', 'apkpure_{category}.list')

        self.app_list = self.p('apkpure_apps.list')
        self.app_versions_dir = self.p('versions')
        self.app_versions_list = self.p('versions', '{pkg}.json')
        self.test_list = self.p('test.list')
        self.versions_done = self.p('versions.done')
        self.per_version_download_done = self.p('per_version_done', '{pkg}.done')

        # manifests
        self.manifest_dir = self.p('manifests')
        self.manifest_file = self.p('manifests', '{pkg}', '{apk}')
        self.manifest_file_long = self.p('manifests', '{pkg}', '{pkg}--{ver}.xml')
        self.manifests_download_done = self.p('manifests.done')  # not used

        # download
        self.downloaded_apks_dir = self.p('downloads')
        self.downloaded_apk_file = self.p('downloads', '{pkg}', '{pkg}--{ver}.apk')

        # extracted permissions
        self.permissions_dir = self.p('permissions')
        self.permissions_file = self.p('permissions', '{pkg}', '{apk}')
        self.permissions_done = self.p('permissions_done', '{pkg}.done')

        self.permissions_pkg_file = self.p('permissions_pkg', '{pkg}')
        self.permissions_pkg_dir = self.p('permissions_pkg')

        # autogranted
        self.autogranted_dir = self.p('autogranted')
        self.autogranted_file = self.p('autogranted', 'autogranted.json')
        self.autogranted_filtered_file = self.p('autogranted', 'autogranted_filtered.json')
        self.autogranted_stats_file = self.p('autogranted', 'autogranted_stats.json')
        self.autogranted_extra_stats_file = self.p('autogranted', 'autogranted_extra_stats.json')
        #
        self.api_dir = self.p('api')
        self.api_file = self.p('api', '{permission}', '{pkg}--{ver}.json')
        # entry
        self.entry_points_dir = self.p('entrypoints')
        self.entry_points_file = self.p('entrypoints', '{permission}', '{pkg}--{ver}.json')
        #
        self.api_usage = self.p('api_usage_stats.json')
        # descriptions
        self.description_file = self.p('apkpure_apps.description')
        self.description_plain_file = self.p('apkpure_apps_plain.description')
        self.whatsnew_file = self.p('whatsnew_apkpure_apps.json')
        self.whatsnew_plain_file = self.p('whatsnew_apkpure_apps.txt')

        # stats
        self.stats_one_version = self.p('apkpure_one_version_pkg.list')
        self.stats_old_pkg = self.p('apkpure_old_pkg.list')
        self.stats_overgranted = self.p('apkpure_overgranted.json')
        # callgraph
        self.callgraph_file = self.p('callgraph', '{pkg}--{ver}{suffix}.json')
        self.callgraph_stat_file = self.p('callgraph.list')


class ApkpureGamesConfig(Config):
    category_url = '{base_url}/game'
    category_index = 0

    data_folder = 'data_games'

    def __init__(self):
        super().__init__()

        self.categories_list = self.p('apkpure_categories.list')
        self.app_category_list = self.p('categories', 'apkpure_{category}.list')

        self.app_list = self.p('apkpure_apps.list')
        self.app_versions_dir = self.p('versions')
        self.app_versions_list = self.p('versions', '{pkg}.json')
        self.test_list = self.p('test.list')
        self.versions_done = self.p('versions.done')
        self.per_version_download_done = self.p('per_version_done', '{pkg}.done')

        # manifests
        self.manifest_dir = self.p('manifests')
        self.manifest_file = self.p('manifests', '{pkg}', '{apk}')
        self.manifest_file_long = self.p('manifests', '{pkg}', '{pkg}--{ver}.xml')
        self.manifests_download_done = self.p('manifests.done')  # not used

        # download
        self.downloaded_apks_dir = self.p('downloads')
        self.downloaded_apk_file = self.p('downloads', '{pkg}', '{pkg}--{ver}.apk')

        # extracted permissions
        self.permissions_dir = self.p('permissions')
        self.permissions_file = self.p('permissions', '{pkg}', '{apk}')
        self.permissions_done = self.p('permissions_done', '{pkg}.done')

        self.permissions_pkg_file = self.p('permissions_pkg', '{pkg}')
        self.permissions_pkg_dir = self.p('permissions_pkg')

        # autogranted
        self.autogranted_dir = self.p('autogranted')
        self.autogranted_file = self.p('autogranted', 'autogranted.json')
        self.autogranted_filtered_file = self.p('autogranted', 'autogranted_filtered.json')
        self.autogranted_stats_file = self.p('autogranted', 'autogranted_stats.json')
        self.autogranted_extra_stats_file = self.p('autogranted', 'autogranted_extra_stats.json')
        #
        self.api_dir = self.p('api')
        self.api_file = self.p('api', '{permission}', '{pkg}--{ver}.json')
        # descriptions
        self.description_file = self.p('apkpure_apps.description')
        self.description_plain_file = self.p('apkpure_apps_plain.description')
        self.whatsnew_file = self.p('whatsnew_apkpure_apps.json')
        self.whatsnew_plain_file = self.p('whatsnew_apkpure_apps.txt')

        # stats
        self.stats_one_version = self.p('apkpure_one_version_pkg.list')
        self.stats_old_pkg = self.p('apkpure_old_pkg.list')
        self.stats_overgranted = self.p('apkpure_overgranted.json')


class ZooConfig(Config):
    # apkpure
    data_folder = 'zoo_data'

    def __init__(self):
        # androzoo
        super().__init__()
        self.versions_dir = self.p('zoo_versions')
        self.versions_list = self.p('zoo_versions', '{app}.json')
        self.versions_done = self.p('zoo_versions.done')
        self.apk = self.p('zoo_downloads', '{pkg}', '{apk}')
        self.download_done = self.p('zoo_downloads.done')
        self.per_version_download_done = self.p('zoo_per_version_done', '{pkg}.done')

        self.manifest_dir = self.p('zoo_manifests')
        self.manifest_file = self.p('zoo_manifests', '{pkg}', '{apk}')
        self.manifest_file_long = self.p('zoo_manifests', '{pkg}', '{pkg}--{ver}.xml')

        self.permissions_pkg_file = self.p('zoo_permissions_pkg', '{pkg}')
        self.permissions_pkg_dir = self.p('zoo_permissions_pkg')
        self.autogranted_dir = self.p('zoo_autogranted')
        self.autogranted_file = self.p('zoo_autogranted', 'autogranted.json')
        self.autogranted_filtered_file = self.p('zoo_autogranted', 'autogranted_filtered.json')
        self.autogranted_stats_file = self.p('zoo_autogranted', 'autogranted_stats.json')
        self.autogranted_extra_stats_file = self.p('zoo_autogranted', 'autogranted_extra_stats.json')
        self.stats_one_version = self.p('zoo_one_version_pkg.list')
        self.stats_old_pkg = self.p('zoo_old_pkg.list')

        self.app_versions_list = self.p('zoo_versions', '{pkg}.json')
        # download
        self.downloaded_apks_dir = self.p('zoo_downloads')
        self.downloaded_apk_file = self.p('zoo_downloads', '{pkg}', '{pkg}--{ver}.apk')
        # entry
        self.entry_points_dir = self.p('entrypoints')
        self.entry_points_file = self.p('entrypoints', '{permission}', '{pkg}--{ver}.json')
        #
        self.api_usage = self.p('api_usage_stats.json')
        #
        self.api_dir = self.p('zoo_api')
        self.api_file = self.p('zoo_api', '{permission}', '{pkg}--{ver}.json')
        self.stats_overgranted = self.p('zoo_overgranted.json')
        # callgraph
        self.callgraph_file = self.p('callgraph', '{pkg}--{ver}{suffix}.json')
        self.callgraph_stat_file = self.p('callgraph.list')
