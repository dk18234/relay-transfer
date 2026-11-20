#!/usr/bin/env python3
"""Real HTTP smoke against the Compose stack, including an application SIGKILL."""
import hashlib,json,subprocess,tempfile,time,urllib.request
from pathlib import Path
from relay import Client,digest

def ready():
    for _ in range(180):
        try:
            with urllib.request.urlopen('http://localhost:8082/actuator/health',timeout=2) as r:
                if r.status==200:return
        except Exception:pass
        time.sleep(1)
    raise RuntimeError('Container did not become ready')

def main():
    env=dict(line.split('=',1) for line in Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#'));client=Client('http://localhost:8082',env['ADMIN_TOKEN']);ready()
    with tempfile.TemporaryDirectory() as d:
        source=Path(d)/'dataset.bin';block=bytes(range(256))*4096
        with source.open('wb') as out:
            for _ in range(24):out.write(block)
        sha=digest(source);t=client.request('/transfers','POST',{'name':source.name,'size':source.stat().st_size,'sha256':sha,'requestKey':'container_restart_fixture'});id=t['id'];chunk=t['chunkSize']
        def send(n):
            with source.open('rb') as f:f.seek((n-1)*chunk);b=f.read(chunk)
            client.request(f'/transfers/{id}/parts/{n}','PUT',b,{'X-Chunk-SHA256':hashlib.sha256(b).hexdigest(),'Content-Type':'application/octet-stream'})
        send(1);subprocess.run(['docker','compose','kill','-s','SIGKILL','app'],check=True);subprocess.run(['docker','compose','up','-d','app'],check=True);ready();t=client.request('/transfers/'+id);assert sum(bool(p.get('acceptedAt')) for p in t['parts'])==1
        for n in range(2,t['partCount']+1):send(n)
        t=client.request('/transfers/'+id+'/complete','POST')
        for _ in range(120):
            if t['status']!='VERIFYING':break
            time.sleep(0.5);t=client.request('/transfers/'+id)
        assert t['status']=='COMPLETED',t
        h=hashlib.sha256()
        with client.request('/transfers/'+id+'/download',raw=True) as r:
            for b in iter(lambda:r.read(64*1024),b''):h.update(b)
        assert h.hexdigest()==sha
        print(json.dumps({'containerRestart':'passed','bytes':source.stat().st_size,'sha256':sha,'downloadMatches':True}))
if __name__=='__main__':main()
