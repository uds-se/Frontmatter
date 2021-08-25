import argparse
import json
import os
import sqlite3

import tqdm


class SQLiteHelper:
    create_pkg_table = ('''CREATE TABLE IF NOT EXISTS pkgs (
                                pid integer primary key autoincrement,
                                pkg text,
                                version text);''')
    create_index = ('''CREATE UNIQUE INDEX IF NOT EXISTS pkgs_pkg_version_uindex
                                on pkgs (pkg, version);''')
    create_activity_table = ('''CREATE TABLE IF NOT EXISTS activities (
                                aid integer primary key autoincrement,
                                activity text,
                                title text,
                                is_main boolean,
                                pkg_id integer,
                                FOREIGN KEY (pkg_id) REFERENCES pkgs (pid)
                                );''')
    create_widget_table = ('''CREATE TABLE IF NOT EXISTS widgets (
                                wid integer primary key autoincrement,
                                view_class text,
                                view_id text,
                                id_var text,
                                hid text,
                                parent_hid text,
                                activity_id integer,
                                FOREIGN KEY (activity_id) REFERENCES activities (aid)
                                );''')
    create_text_table = ('''CREATE TABLE IF NOT EXISTS texts (
                                tid integer primary key autoincrement,
                                label text,
                                label_var text,
                                label_type text,
                                widget_id integer,
                                FOREIGN KEY (widget_id) REFERENCES widgets (wid)
                                );''')
    create_drawable_table = ('''CREATE TABLE IF NOT EXISTS drawables (
                                did integer primary key autoincrement,
                                drawable text,
                                drawable_var text,
                                drawable_type text,
                                widget_id integer,
                                FOREIGN KEY (widget_id) REFERENCES widgets (wid)
                                );''')

    add_pkg = ("""INSERT INTO pkgs
               (pid, pkg, version)
               VALUES (NULL, ?, ?)""")
    # NULL
    add_activity = ("""INSERT INTO activities
                    (aid, activity, title, pkg_id, is_main)
                    VALUES (NULL, ?, ?, ?, ?)""")

    add_widget = ("""INSERT INTO widgets
                  (wid, view_class, view_id, id_var, hid, parent_hid, activity_id)
                  VALUES (NULL, ?, ?, ?, ?, ?, ?)""")

    add_text = ("""INSERT INTO texts
                (tid, label, label_var, label_type, widget_id)
                VALUES (NULL, ?, ?, ?, ?)""")

    add_drawable = ("INSERT INTO drawables "
                    "(did, drawable, drawable_var, drawable_type, widget_id) "
                    "VALUES (NULL, ?, ?, ?, ?)")

    create_appwidgets_view = """CREATE VIEW IF NOT EXISTS appwidgets
        AS
        SELECT DISTINCT *  from pkgs
        inner join activities a on pkgs.pid = a.pkg_id
        inner join widgets w on a.aid = w.activity_id
        inner join texts t on w.wid = t.widget_id
        inner join drawables d on w.wid = d.widget_id
        """
    create_app_widgets_view = """CREATE VIEW IF NOT EXISTS 'app-widgets'
        AS
        SELECT DISTINCT *  from pkgs
        inner join activities a on pkgs.pid = a.pkg_id
        inner join widgets w on a.aid = w.activity_id
        inner join texts t on w.wid = t.widget_id
        inner join drawables d on w.wid = d.widget_id
        """
    create_app_with_login_view = """
    CREATE VIEW IF NOT EXISTS 'apps_with_login_view' AS
        SELECT DISTINCT pkgs.*, a.*  from pkgs
        inner join activities a on pkgs.pid = a.pkg_id
        inner join widgets w on a.aid = w.activity_id
        left join texts t on w.wid = t.widget_id
        where (t.label like '%login%') OR (t.label like '%sign in%')
        """

    def __init__(self):
        self.conn = sqlite3.connect('frontmatter.sqlite')

    def __del__(self):
        self.conn.close()

    def create_tables(self):
        c = self.conn.cursor()
        c.execute(self.create_pkg_table)
        c.execute(self.create_index)
        c.execute(self.create_activity_table)
        c.execute(self.create_widget_table)
        c.execute(self.create_text_table)
        c.execute(self.create_drawable_table)
        self.conn.commit()

    def create_view(self):
        c = self.conn.cursor()
        c.execute(self.create_app_widgets_view)
        c.execute(self.create_appwidgets_view)
        c.execute(self.create_app_with_login_view)
        self.conn.commit()

def get_pkg(apk_path):
    apk_file = os.path.basename(apk_path)
    apk_name = os.path.splitext(apk_file)[0]
    if '--' in apk_name:
        pkg, ver = apk_name.split('--')[0:2]
        return pkg, ver
    else:
        return apk_name, ''


def import_apk(apk_path, conn):
    cursor = conn.cursor()
    with open(apk_path) as fd:
        data = json.load(fd)
    pkg_name, pkg_ver = get_pkg(apk_path)
    cursor.execute(SQLiteHelper.add_pkg, (pkg_name, pkg_ver))
    pid = cursor.lastrowid
    widget_idx = 0
    if 'error' in data:
        return
    for activity in data['activities']:
        a_label = activity['label']
        a_name = activity['activity']
        is_main = activity in data['mainActivities']
        cursor.execute(SQLiteHelper.add_activity, (a_name, a_label, pid, is_main))
        aid = cursor.lastrowid
        for layout in activity['layouts']:
            worklist = list()  # tuple (view, parent_hid)
            worklist.append((layout, '0'))  # 0 means root level
            while len(worklist) > 0:
                widget, parent_idx = worklist.pop()
                # print(widget)
                widget_idx += 1
                view_class = widget['viewClass']
                view_id = widget['id']
                id_var = widget['idVariable'] if 'idVariable' in widget else ""
                cursor.execute(SQLiteHelper.add_widget, (view_class, view_id, id_var, widget_idx, parent_idx, aid))
                wid = cursor.lastrowid
                if 'textAttributes' in widget and len(widget['textAttributes']) > 0:  # save text
                    for label_key, label_value in widget['textAttributes'].items():
                        if type(label_value) == dict:
                            label = label_value['value'].strip()
                            label_var = label_value['variable']
                        else:
                            label = label_value.strip()
                            label_var = 'no_var'
                        label_type = label_key if not label_key[-1].isdigit() else 'dynamic'
                        if len(label) > 0:
                            cursor.execute(SQLiteHelper.add_text, (label, label_var, label_type, wid))
                if 'otherAttributes' in widget and len(widget['otherAttributes']) > 0:  # save text
                    for attr_key, attr_value in widget['otherAttributes'].items():
                        if attr_key == 'src' or attr_key == 'background':
                            if type(attr_value) == dict:
                                value = attr_value['value'].strip()
                                var = attr_value['variable']
                            else:
                                value = attr_value
                                var = 'no_var'
                            d_type = attr_key
                            cursor.execute(SQLiteHelper.add_drawable, (value, var, d_type, wid))
                if 'children' in widget and len(widget['children']) > 0:
                    for child in widget['children']:
                        worklist.append((child, widget_idx))

        for fragment in activity['fragments']:
            if 'layout' not in fragment:
                continue
            layout = fragment['layout']
            worklist = list()  # tuple (view, parent_hid)
            worklist.append((layout, '0'))  # 0 means root level
            while len(worklist) > 0:
                widget, parent_idx = worklist.pop()
                # print(widget)
                widget_idx += 1
                view_class = widget['viewClass']
                view_id = widget['id']
                id_var = widget['idVariable'] if 'idVariable' in widget else ""
                cursor.execute(SQLiteHelper.add_widget, (view_class, view_id, id_var, widget_idx, parent_idx, aid))
                wid = cursor.lastrowid
                if 'textAttributes' in widget and len(widget['textAttributes']) > 0:  # save text
                    for label_key, label_value in widget['textAttributes'].items():
                        if type(label_value) == dict:
                            label = label_value['value'].strip()
                            label_var = label_value['variable']
                        else:
                            label = label_value.strip()
                            label_var = 'no_var'
                        label_type = label_key if not label_key[-1].isdigit() else 'dynamic'
                        if len(label) > 0:
                            cursor.execute(SQLiteHelper.add_text, (label, label_var, label_type, wid))
                if 'otherAttributes' in widget and len(widget['otherAttributes']) > 0:  # save text
                    for attr_key, attr_value in widget['otherAttributes'].items():
                        if attr_key == 'src' or attr_key == 'background':
                            if type(attr_value) == dict:
                                value = attr_value['value'].strip()
                                var = attr_value['variable']
                            else:
                                value = attr_value
                                var = 'no_var'
                            d_type = attr_key
                            cursor.execute(SQLiteHelper.add_drawable, (value, var, d_type, wid))
                if 'children' in widget and len(widget['children']) > 0:
                    for child in widget['children']:
                        worklist.append((child, widget_idx))

    conn.commit()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('-i', '--input')
    parser.add_argument('-v', '--views', action='store_true')
    args = parser.parse_args()
    sqlite = SQLiteHelper()
    sqlite.create_tables()
    root_folder = args.input
    if args.input is not None:
        for apk in tqdm.tqdm(os.listdir(root_folder)):
            if not apk.endswith('.json'):
                continue
            apk_path = os.path.join(root_folder, apk)
            try:
                import_apk(apk_path, sqlite.conn)
            except sqlite3.IntegrityError as e:
                print(f"{apk} already added")
    if args.views is not None:
        sqlite.create_view()
