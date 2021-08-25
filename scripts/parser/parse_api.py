"""
This script is used to extract api data from frontmatter results and combine them with the ui results (connecting api calls with ui labels)
It takes two folders: with api data and ui data (files should be named equally) and produces one csv table
"""
import argparse
import json
import os
from pathlib import Path

import pandas as pd
from tqdm import tqdm

from frontmatter_parser import FrontmatterUiParser


def process_ui_file(file_path: Path) -> FrontmatterUiParser:
    frontmatter = FrontmatterUiParser(file_path)
    frontmatter.read_ui()
    return frontmatter


def process_api(frontmatter_results: FrontmatterUiParser):
    corpus = []
    pkg = frontmatter_results.pkg
    ui_data = frontmatter_results.ui
    for view in ui_data:
        apis = view.api
        view_labels = view.label
        activity_name = view.activity
        if not view_labels:
            continue
        for api in apis:
            corpus.append({'pkg': pkg, 'activity': activity_name, 'api': api, 'label': view_labels})
    return corpus


def get_pkg_name(path):
    basename = os.path.basename(path)
    pkg = os.path.splitext(basename)[0]
    return pkg


def read_data(ui_path, api_path):
    corpus = []
    for data_file in tqdm(os.listdir(api_path)):
        if not data_file.endswith('json'):
            continue
        api_file_path = Path(api_path) / data_file
        ui_file_path = Path(ui_path) / data_file
        try:
            frontmatter_results = process_ui_file(ui_file_path)
            frontmatter_results.read_api(api_file_path, sensitive_only=True)
            api_data = process_api(frontmatter_results)
            if api_data:
                corpus.extend(api_data)
        except:
            print(f"Error in processing: {data_file}")
    return pd.DataFrame(corpus)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('-u', '--ui-dir')
    parser.add_argument('-a', '--api-dir')
    parser.add_argument('-o', '--output-file')
    args = parser.parse_args()
    api_data = read_data(args.ui_dir, args.api_dir)
    api_data.to_csv(args.output_file)
