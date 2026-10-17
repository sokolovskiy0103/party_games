"""Run one of the workflow templates next to this file on the local ComfyUI and download the results.

    python -I gen.py <sfx|music|still|loop> --out <dir> [--timeout 900] NODE.FIELD=VALUE ...

VALUE is parsed as JSON when it can be (numbers, true/false), otherwise taken as a string. Prints the downloaded
file paths, one per line. Needs `comfy` (comfy-cli) on PATH and ComfyUI running at 127.0.0.1:8188.
"""
import json
import os
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
if not args or args[0] not in ('sfx', 'music', 'still', 'loop') or '--out' not in args:
    sys.exit(__doc__)
kind, out, timeout, sets = args[0], None, '900', []
i = 1
while i < len(args):
    if args[i] == '--out': out = args[i + 1]; i += 2
    elif args[i] == '--timeout': timeout = args[i + 1]; i += 2
    else: sets.append(args[i]); i += 1

with open(os.path.join(here, kind + '.json'), encoding='utf8') as f:
    g = json.load(f)
for s in sets:
    key, _, raw = s.partition('=')
    node, _, field = key.partition('.')
    if node not in g or field not in g[node]['inputs']:
        sys.exit(f'unknown field {key} in {kind}.json')
    try: val = json.loads(raw)
    except ValueError: val = raw
    g[node]['inputs'][field] = val

fd, wf = tempfile.mkstemp(suffix='.json')
with os.fdopen(fd, 'w', encoding='utf8') as f:
    json.dump(g, f, ensure_ascii=False)
try:
    r = subprocess.run(['comfy', '--json', 'run', '--workflow', wf, '--wait', '--timeout', timeout],
                       capture_output=True, text=True, encoding='utf8')
finally:
    os.remove(wf)
env = json.loads(r.stdout.strip().splitlines()[-1])
if not env.get('ok'):
    sys.exit(json.dumps(env.get('error'), ensure_ascii=False))
pid = env['data']['prompt_id']
os.makedirs(out, exist_ok=True)
before = set(os.listdir(out))
d = subprocess.run(['comfy', '--json', 'download', pid, '--out-dir', out], capture_output=True, text=True, encoding='utf8')
denv = json.loads(d.stdout.strip().splitlines()[-1])
if not denv.get('ok'):
    # an unchanged graph is served from ComfyUI's cache with the old file names; change the seed to get new files
    sys.exit(json.dumps(denv.get('error'), ensure_ascii=False))
for name in sorted(set(os.listdir(out)) - before):
    print(os.path.join(out, name))
