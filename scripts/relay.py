#!/usr/bin/env python3
"""Bounded-memory CLI for the real Java backend. Python standard library only."""
import argparse, concurrent.futures, hashlib, json, os, sys, time, urllib.error, urllib.request, uuid
from pathlib import Path

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

class Client:
    def __init__(self,url,token):self.url=url.rstrip('/');self.token=token
    def request(self,path,method='GET',body=None,headers=None,raw=False):
        h={'X-Admin-Token':self.token,**(headers or {})}
        if isinstance(body,dict):body=json.dumps(body).encode();h['Content-Type']='application/json'
        req=urllib.request.Request(self.url+'/api'+path,data=body,method=method,headers=h)
        try:
            response=urllib.request.urlopen(req,timeout=180)
            if raw:return response
            with response:return json.load(response)
        except urllib.error.HTTPError as e:
            detail=e.read().decode(errors='replace')
            raise RuntimeError(f'HTTP {e.code}: {detail}') from e

def save_state(path,state):
    path.parent.mkdir(parents=True,exist_ok=True);temp=path.with_suffix(path.suffix+'.tmp');temp.write_text(json.dumps(state,indent=2)+'\n');os.replace(temp,path)

def upload(args,client):
    path=Path(args.file).resolve();sha=digest(path);size=path.stat().st_size
    session=Path(args.session)
    if session.exists():
        state=json.loads(session.read_text())
        if state['sha256']!=sha or state['size']!=size:raise RuntimeError('The file differs from the saved session fingerprint')
        if state['url']!=client.url:raise RuntimeError('Saved session belongs to another service URL')
    else:
        state={'url':client.url,'name':path.name,'size':size,'sha256':sha,'requestKey':str(uuid.uuid4())};save_state(session,state)
    if 'id' not in state:
        t=client.request('/transfers','POST',{'name':state['name'],'size':size,'sha256':sha,'requestKey':state['requestKey']});state['id']=t['id'];save_state(session,state)
    id=state['id'];t=client.request('/transfers/'+id)
    if t['expectedSha256']!=sha or t['size']!=size:raise RuntimeError('Server manifest does not match the original file')
    missing=[p for p in t['parts'] if not p.get('acceptedAt')]
    print(f"Transfer {id}: {len(t['parts'])-len(missing)}/{len(t['parts'])} already accepted",flush=True)
    if t['status'] in ['FAILED_INTEGRITY','CANCELLED','EXPIRED']:raise RuntimeError('Session is '+t['status'])
    if t['status']=='VERIFY_FAILED':t=client.request('/transfers/'+id+'/retry-verification','POST')
    chosen=missing[:args.pause_after] if args.pause_after else missing
    def send(p):
        with path.open('rb') as source:source.seek((p['number']-1)*t['chunkSize']);data=source.read(p['size'])
        part_sha=hashlib.sha256(data).hexdigest()
        if p['number']==args.corrupt_part:data=bytes([data[0]^255])+data[1:]
        for attempt in range(3):
            try:
                result=client.request(f"/transfers/{id}/parts/{p['number']}",'PUT',data,{'X-Chunk-SHA256':part_sha,'Content-Type':'application/octet-stream'})
                print(f"Chunk {p['number']} accepted"+(' (duplicate)' if result['duplicate'] else ''),flush=True);return
            except RuntimeError as e:
                if attempt==2 or not any(code in str(e) for code in ['HTTP 429','HTTP 503','HTTP 502','HTTP 504']):raise
                time.sleep(0.5*2**attempt)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as workers:list(workers.map(send,chosen))
    t=client.request('/transfers/'+id)
    if any(not p.get('acceptedAt') for p in t['parts']):print('Paused. Rerun the same command without --pause-after to send only missing chunks.');return
    t=client.request('/transfers/'+id+'/complete','POST')
    deadline=time.monotonic()+args.timeout
    while t['status']=='VERIFYING' and time.monotonic()<deadline:time.sleep(1);t=client.request('/transfers/'+id)
    if t['status']!='COMPLETED':raise RuntimeError('Final state: '+t['status']+' '+str(t.get('lastError','')))
    print('Verified SHA-256: '+t['actualSha256']);receipt=session.with_suffix('.receipt.json');receipt.write_text(json.dumps(t,indent=2)+'\n');print('Receipt: '+str(receipt))
    if args.download:download(client,id,Path(args.download))

def download(client,id,target):
    temp=target.with_suffix(target.suffix+'.partial');sha=hashlib.sha256()
    try:
        with client.request('/transfers/'+id+'/download',raw=True) as response,temp.open('wb') as out:
            expected=response.headers['X-File-SHA256']
            for block in iter(lambda:response.read(64*1024),b''):out.write(block);sha.update(block)
        if sha.hexdigest()!=expected:raise RuntimeError('Downloaded file failed independent SHA-256 verification')
        os.replace(temp,target);print('Downloaded and verified: '+str(target))
    finally:temp.unlink(missing_ok=True)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://localhost:8082');p.add_argument('--token',default=os.environ.get('ADMIN_TOKEN',''))
    sub=p.add_subparsers(dest='command',required=True);u=sub.add_parser('upload');u.add_argument('file');u.add_argument('--session',default='relay-session.json');u.add_argument('--workers',type=int,choices=range(1,5),default=3);u.add_argument('--pause-after',type=int,default=0);u.add_argument('--corrupt-part',type=int,default=0);u.add_argument('--timeout',type=int,default=600);u.add_argument('--download')
    d=sub.add_parser('download');d.add_argument('id');d.add_argument('output')
    s=sub.add_parser('status');s.add_argument('id')
    a=p.parse_args()
    if not a.token:p.error('Set ADMIN_TOKEN in the shell or pass --token')
    client=Client(a.url,a.token)
    try:
        if a.command=='upload':upload(a,client)
        elif a.command=='download':download(client,a.id,Path(a.output))
        else:print(json.dumps(client.request('/transfers/'+a.id),indent=2))
    except(Exception,KeyboardInterrupt)as e:print(str(e) or 'Interrupted; saved progress can be resumed.',file=sys.stderr);return 1
    return 0
if __name__=='__main__':sys.exit(main())
