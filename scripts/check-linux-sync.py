"""Exercise the real Linux persona copy block without installing OpenClaw or creating users."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'deploy/linux.sh').read_text(encoding='utf-8')
block = source[source.index('for role in phanes'):source.index('if [[ ! -f "$STATE/gateway.token"')]
with tempfile.TemporaryDirectory() as directory:
    state = Path(directory)
    for role in ['phanes', 'ronova', 'naberius', 'istaroth', 'asmoday']:
        workspace = state / 'workspaces' / role
        workspace.mkdir(parents=True)
        for name in ['AGENTS.md', 'SOUL.md', 'TOOLS.md']:
            (workspace / name).write_text('custom ' + name)
        (workspace / 'MEMORY.md').write_bytes(b'PRIVATE MEMORY PRESERVED\n')
    env = os.environ | {'ROOT': str(root), 'STATE': directory}
    subprocess.run(['bash', '-eu', '-c', block], env=env, check=True)
    subprocess.run(['bash', '-eu', '-c', block], env=env, check=True)
    assert len(list(state.glob('workspaces/*/*.bak'))) == 15
    assert all(p.read_bytes() == b'PRIVATE MEMORY PRESERVED\n' for p in state.glob('workspaces/*/MEMORY.md'))
print('PASS Linux actual sync block repeated: 15 backups, five memories unchanged')
