"""Run only in a NEW isolated Paper directory. Never point this at a live server.
Requires Paper bootstrap/libraries, both build/libs JARs and mineflayer already installed.
The fixture EULA must already be accepted. Model responses here are deterministic test data.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--server', required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--mineflayer', required=True)
options = parser.parse_args()
root = Path(options.server).resolve()
repo = Path(__file__).resolve().parent.parent
if not (root / 'eula.txt').is_file() or 'eula=true' not in (root / 'eula.txt').read_text(encoding='utf-8'):
    raise SystemExit('Accept the Minecraft EULA in the isolated fixture before running.')
if (root / 'world').exists():
    raise SystemExit('Use a NEW isolated directory; existing worlds are not modified by this runner.')
plugins = root / 'plugins'
plugins.mkdir(exist_ok=True)
import shutil
shutil.copy2(repo / 'build/libs/SkyIslandSystem-0.7.0-beta.2.jar', plugins)
shutil.copy2(repo / 'build/libs/SkyIslandTestDriver.jar', plugins)
sky = plugins / 'SkyIslandSystem'
sky.mkdir(exist_ok=True)
(sky / 'config.yml').write_text('gateway-url: http://127.0.0.1:19879\ncommand-mode: world-autonomous\nai-paused: false\nreview-interval-seconds: 600\n', encoding='utf-8')
(sky / 'secrets.yml').write_text('gateway-token: fixture-only-local\n', encoding='utf-8')
(root / 'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=25579\nlevel-type=minecraft:flat\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\nmax-players=4\nmotd=SkyIsland isolated acceptance\nenable-rcon=false\n', encoding='utf-8')
requests = []
query_count = 0
class Gateway(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def handle(self):
        try: super().handle()
        except ConnectionResetError: pass  # Expected when the isolated Paper process stops.
    def do_POST(self):
        global query_count
        req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        prompt = req['messages'][0]['content']
        role = req['model'].split('/')[-1]
        requests.append({'role':role, 'phase': 'QUERY' if 'V070_PAGES' in prompt else 'OTHER', 'user':req.get('user')})
        reply = {'message':'fixture opinion; no invented success', 'action':None}
        if 'V070_PAGES' in prompt and query_count < 8:
            query_count += 1
            reply['query'] = {'type':'capability_catalog','offset':0,'capability':
                              ['time.set','time.add','weather.set','border.set','item.reward','item.hold','item.return','player.teleport'][query_count-1]}
        elif role == 'phanes' and '提案 ID=' in prompt:
            match = re.search(r'提案 ID=([0-9a-f]{8})\nHASH=([0-9a-f]{64})', prompt)
            if match: reply['approval']={'id':match[1],'hash':match[2],'approved':True}
        body = json.dumps({'choices':[{'message':{'content':json.dumps(reply, ensure_ascii=False)}}]},ensure_ascii=False).encode('utf-8')
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
gateway = ThreadingHTTPServer(('127.0.0.1',19879),Gateway)
threading.Thread(target=gateway.serve_forever,daemon=True).start()
server = None
lines = []
def boot():
    ready=threading.Event()
    process=subprocess.Popen([options.java,'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Xms512M','-Xmx2G','-jar','Paper-1.20.1.jar','nogui'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    def reader():
        for line in process.stdout:
            lines.append(line)
            if 'Done (' in line: ready.set()
    threading.Thread(target=reader,daemon=True).start()
    if not ready.wait(90): raise RuntimeError('Paper startup failed: '+''.join(lines[-15:]))
    return process
def cmd(value):
    server.stdin.write(value+'\n');server.stdin.flush()
def wait_check(key, timeout=20):
    until=time.monotonic()+timeout
    report=plugins/'SkyIslandTestDriver/results.json'
    while time.monotonic()<until:
        if report.exists():
            data=json.loads(report.read_text(encoding='utf-8'))
            if key in data:
                if not data[key]:raise AssertionError(key)
                return
        if any('FAIL ' in line for line in lines):raise AssertionError(''.join(line for line in lines if 'FAIL ' in line))
        time.sleep(.25)
    raise TimeoutError(key)
def stop():
    if server and server.poll() is None:
        cmd('stop');server.wait(timeout=40)
try:
    server=boot();cmd('sifixture bootstrap');wait_check('published',30)
    cmd('skyisland ask phanes V070_PAGES investigate eight different capabilities')
    time.sleep(15)
    if query_count != 8:raise AssertionError('fixed investigation round limit remains: '+str(query_count))
    environment=os.environ.copy();environment['SI_MINEFLAYER']=options.mineflayer;environment['SI_FIXTURE_PASSWORD']=secrets.token_hex(16)
    bot=subprocess.Popen(['node',str(repo/'scripts/paper-fixture/bot.js')],cwd=repo,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
    first=bot.stdout.readline().strip()
    if first!='BOT_VERIFIED':raise AssertionError('bot registration: '+first)
    cmd('sifixture activity SI070Player');wait_check('reward_once',90)
    wait_check('reputation_once');time.sleep(5)
    if bot.stdout.readline().strip()!='BOT_COMMANDS_PASS':raise AssertionError('player commands were not verified')
    cmd('sifixture conflicts');wait_check('restore_conflict_recorded',20)
    cmd('sifixture paused');wait_check('pause_preserved');stop()
    server=boot();cmd('sifixture resumed');wait_check('restart_cursor_resumed',25)
    # Report survives server restart; never infer success from AI message text.
    report=json.loads((plugins/'SkyIslandTestDriver/results.json').read_text(encoding='utf-8'))
    if not all(report.values()):raise AssertionError(report)
    report['gateway_eight_queries']=query_count==8
    report['session_role_routing']=all(row['user']=='skyisland:admin:'+row['role'] for row in requests)
    (root/'acceptance-redacted.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
finally:
    stop();gateway.shutdown()
    if 'bot' in globals() and bot.poll() is None:bot.terminate();bot.wait(timeout=10)
    (root/'fixture-console.log').write_text(''.join(lines),encoding='utf-8')
