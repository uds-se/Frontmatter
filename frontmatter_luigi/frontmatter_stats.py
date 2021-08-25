import json
import os

import tqdm

path = "frontmatter_transitions"
total = 0
little = 0
truncated = 0
empty = 0
timeout = 0
unity = 0
empty_list = []
selected_list = []
for trans_file in tqdm.tqdm(os.listdir(path)):
    total += 1
    with open(os.path.join(path, trans_file)) as fd:
        trans_content = json.load(fd)
        if "activities" not in trans_content:
            timeout += 1
            continue
        activities = trans_content["activities"]
        transitions = trans_content["transitions"]
        main_a = trans_content["mainActivity"]
        for m in main_a:
            if m.startswith("com.unity3d"):
                unity += 1
                continue
        t_count = 0
        for t in transitions:
            if t['src'] != "dummyActivity" and t['src'] != t['dest']:
                t_count += 1
        a_count = len(activities)
        if a_count <= 3:
            little += 1
        elif t_count == 0 and a_count > 3:
            empty_list.append(trans_file)
            empty += 1
        elif a_count > 1.5 * t_count and a_count > 3:
            truncated += 1
        else:
            selected_list.append(trans_file)
with open("empty_res.list", "w")as fd:
    fd.writelines([x + '\n' for x in empty_list])
with open("selected_apps.list", "w")as fd:
    fd.writelines([x + '\n' for x in selected_list])

print("Total apps:", total, ", empty:", empty, " truncated: ", truncated, "timeout:", timeout, "unity:", unity)
