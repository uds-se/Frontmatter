import argparse

from datetime import datetime
from pathlib import Path
from tqdm import tqdm
import pandas as pd

# androzoo_latest_path = Path("latest.csv")
androzoo_latest_path = Path("/Users/kuznetsov/work/workspace/autogranted/latest.csv")
market = "play.google.com"
required_columns = ['sha256', 'dex_date', 'pkg_name', 'vercode', 'markets']
tqdm.pandas()


def query(start: datetime, end: datetime, out_file: Path):
    column_idx = get_column_indices()
    # df_market = pd.DataFrame(columns=required_columns)
    # for chunk in tqdm(pd.read_csv(androzoo_latest_path, sep=',', usecols=column_idx, chunksize=10 ** 3)):
    #     df_market = df_market.append(chunk[chunk['markets'].str.contains(market)])
    df = pd.read_csv(androzoo_latest_path, sep=',', usecols=column_idx)
    df.fillna(0, inplace=True)
    print("latest.csv loaded")
    df.vercode = df.vercode.astype(int)
    df['dex_date'] = pd.to_datetime(df['dex_date'])  # , format='%Y-%m-%d %H:%M:%S'
    df_market = df[df['markets'].str.contains(market)]
    df_date = df_market[(df_market['dex_date'] >= start) & (df_market['dex_date'] < end)]
    df_lastver = df_date.groupby('pkg_name').progress_apply(lambda x: x.nlargest(1, "vercode"))
    df_res = df_lastver.loc[:, ('pkg_name', 'vercode', 'sha256')]
    df_res.to_csv(out_file, index=False)


def get_column_indices():
    with open(androzoo_latest_path) as fd:
        header = fd.readline().strip()
    columns = header.split(",")
    return [columns.index(c) for c in required_columns]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=lambda s: datetime.strptime(s, '%Y-%m-%d'), help="start of the period Y-m-d")
    parser.add_argument("--end", type=lambda s: datetime.strptime(s, '%Y-%m-%d'), help="end of the period Y-m-d")
    parser.add_argument("--output", help="output csv")
    args = parser.parse_args()
    query(args.start, args.end, Path(args.output))
