import argparse
import contextlib
import json
import os
import traceback
from collections import defaultdict, Counter
from pathlib import Path

import joblib
import pandas as pd
from joblib import Parallel, delayed
from tqdm import tqdm

from frontmatter_parser import FrontmatterUiParser


def process_file(ui_file_path, api_file_path: Path, with_api, sensitive_only, force=True):
    """
    :param ui_file_path:
    :param api_file_path:
    :param with_api: whether to collect API or not
    :param sensitive_only: collect only sensitive APIs
    :param force: parse file even if it is considered as unity/platform
    :return:
    """
    l_platform_stats = defaultdict(int)
    l_lang_stats = defaultdict(int)
    l_error_stats = defaultdict(int)
    trans_data = []
    api_data = []
    try:
        frontmatter = FrontmatterUiParser(ui_file_path)
        frontmatter.read_ui(force)
        activity_data = frontmatter.get_activity_data()
        trans_data = frontmatter.get_app_data_transitions()
        if with_api:
            if api_file_path.exists():
                frontmatter.read_api(api_file_path, sensitive_only)
                api_data = frontmatter.collect_api()
            else:
                print(f"Missing api file for {api_file_path}")
        if frontmatter.error != '':
            l_error_stats[frontmatter.error] += 1
        if frontmatter.apk_platform != '':
            l_platform_stats[frontmatter.apk_platform] += 1
        l_lang_stats[frontmatter.lang] += 1
        del frontmatter
    except Exception as err:
        print(ui_file_path)
        traceback.print_last()
        activity_data = []
        # raise err
    return activity_data, trans_data, api_data, l_error_stats, l_platform_stats, l_lang_stats


@contextlib.contextmanager
def tqdm_joblib(tqdm_object):
    """
    Context manager to patch joblib to report into tqdm progress bar given as argument
    * currently not used
    """

    def tqdm_print_progress(self):
        if self.n_completed_tasks > tqdm_object.n:
            n_completed = self.n_completed_tasks - tqdm_object.n
            tqdm_object.update(n=n_completed)

    original_print_progress = joblib.parallel.Parallel.print_progress
    joblib.parallel.Parallel.print_progress = tqdm_print_progress

    try:
        yield tqdm_object
    finally:
        joblib.parallel.Parallel.print_progress = original_print_progress
        tqdm_object.close()


def read_data(ui_path: Path, api_path: Path, with_api, sensitive_only, parallel):
    ui_corpus = []
    api_corpus = []
    transitions_corpus = []
    c_error_stats = Counter()
    c_platform_stats = Counter()
    c_lang_stats = Counter()
    results = read_data_parallel(ui_path, api_path, with_api, sensitive_only) if parallel else read_data_sequential(ui_path, api_path, with_api, sensitive_only)
    for res in results:
        app_data, trans_data, api_data, l_error_stats, l_platform_stats, l_lang_stats = res
        if app_data:
            ui_corpus.extend(app_data)
            api_corpus.extend(api_data)
        if trans_data:
            transitions_corpus.extend(trans_data)
        c_error_stats = c_error_stats + Counter(l_error_stats)
        c_platform_stats = c_platform_stats + Counter(l_platform_stats)
        c_lang_stats = c_lang_stats + Counter(l_lang_stats)
    error_stats = dict(c_error_stats)
    platform_stats = dict(c_platform_stats)
    lang_stats = dict(c_lang_stats)
    if 'unknown' in lang_stats:
        del lang_stats['unknown']
    stats = {'errors': error_stats, 'platform': platform_stats, 'lang': lang_stats}
    return pd.DataFrame(ui_corpus), pd.DataFrame(transitions_corpus), pd.DataFrame(api_corpus), stats


def read_data_parallel(ui_path: Path, api_path: Path, with_api, sensitive_only):
    n_jobs = max(1, os.cpu_count() - 1)
    file_paths = [(data_file, api_path / data_file.name) for data_file in ui_path.iterdir() if data_file.suffix.endswith('json')]
    if with_api:
        file_paths = list(filter(lambda x: x[1].exists(), file_paths))
    results = Parallel(n_jobs=n_jobs)(delayed(process_file)(data_file, api_file, with_api, sensitive_only) for data_file, api_file in tqdm(file_paths))
    return results


def read_data_sequential(ui_path: Path, api_path: Path, with_api, sensitive_only):
    results = []
    for data_file in tqdm(ui_path.iterdir()):
        if not data_file.suffix.endswith('json'):
            continue
        file_path = data_file
        api_file_path = api_path / data_file.name
        if with_api and not api_file_path.exists():
            continue
        res = process_file(file_path, api_file_path, with_api, sensitive_only)
        results.append(res)
    return results


def save_data(corpus, path):
    corpus = corpus.drop_duplicates()
    corpus.to_csv(path, index=False)


def save_stats(stats_file):
    with open(stats_file, "w") as fd:
        json.dump(stats, fd)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('-d', '--data-dir', help="folder with json ui analysis results")
    parser.add_argument('-a', '--api-dir', help="folder with json api analysis results")
    parser.add_argument('--ui', help="output csv file for extracted activities")
    parser.add_argument('-p', '--parallel', default=False, action='store_true', help="use parallel processing")
    parser.add_argument('-t', '--transitions-file', help="also extract transitions into this file (specify path)")
    parser.add_argument('--api', help="output csv file for activity apis")
    parser.add_argument('-s', '--stats-file', help="output csv file for stats")
    parser.add_argument('--ignore-filtering', action='store_true', default=False, help="don't filter out framework apps")
    parser.add_argument('--sensitive_only', default=False, action='store_true', help="collect only sensitive APIs")
    parser.add_argument('--with-api', default=False, action='store_true')
    args = parser.parse_args()
    data_dir = Path(args.data_dir)
    api_dir = Path(args.api_dir)
    ui_data, transitions_data, api_features, stats = read_data(data_dir, api_dir, args.with_api, args.sensitive_only, args.parallel)
    save_data(ui_data, args.output_file)  # save ui res
    if api_features:
        save_data(api_features, args.api_features)  # save api res
    if args.transitions_file:
        save_data(transitions_data, args.transitions_file)
    save_stats(args.stats_file)
